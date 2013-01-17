/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl;

import com.hazelcast.cluster.JoinOperation;
import com.hazelcast.core.OperationTimeoutException;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.partition.PartitionInfo;
import com.hazelcast.spi.*;
import com.hazelcast.spi.exception.RetryableException;
import com.hazelcast.spi.exception.TargetNotMemberException;
import com.hazelcast.spi.exception.WrongTargetException;
import com.hazelcast.util.Clock;
import com.hazelcast.util.Util;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.logging.Level;

abstract class InvocationImpl implements Future, Invocation, Callback {
    private static final Object NULL_RESPONSE = new Object();

    private final BlockingQueue<Object> responseQ = new LinkedBlockingQueue<Object>();
    private final long callTimeout;
    protected final NodeEngineImpl nodeEngine;
    protected final String serviceName;
    protected final Operation op;
    protected final int partitionId;
    protected final int replicaIndex;
    protected final int tryCount;
    protected final long tryPauseMillis;
    protected final ILogger logger;
    private volatile int invokeCount = 0;
    private volatile boolean done = false;

    InvocationImpl(NodeEngineImpl nodeEngine, String serviceName, Operation op, int partitionId,
                   int replicaIndex, int tryCount, long tryPauseMillis, long callTimeout) {
        this.nodeEngine = nodeEngine;
        this.serviceName = serviceName;
        this.op = op;
        this.partitionId = partitionId;
        this.replicaIndex = replicaIndex;
        this.tryCount = tryCount;
        this.tryPauseMillis = tryPauseMillis;
        this.callTimeout = getCallTimeout(callTimeout);
        this.logger = nodeEngine.getLogger(Invocation.class.getName());
    }

    private long getCallTimeout(long callTimeout) {
        if (callTimeout > 0) {
            return callTimeout;
        }
        final long defaultCallTimeout = nodeEngine.operationService.getDefaultCallTimeout();
        if (op instanceof WaitSupport) {
            final long waitTimeoutMillis = ((WaitSupport) op).getWaitTimeoutMillis();
            if (waitTimeoutMillis > 0 && waitTimeoutMillis < Long.MAX_VALUE && defaultCallTimeout > 5000) {
                return waitTimeoutMillis  + 5000;
            }
        }
        return defaultCallTimeout;
    }

    public void notify(Object result) {
        setResult(result);
    }

    protected abstract Address getTarget();

    public final Future invoke() {
        if (invokeCount > 0) {
            throw new IllegalStateException("An invocation can not be invoked more than once!");
        }
        try {
            checkOperationType(op);
            OperationAccessor.setCallTimeout(op, callTimeout);
            doInvoke();
        } catch (Exception e) {
            if (e instanceof RetryableException) {
                setResult(e);
            } else {
                Util.throwUncheckedException(e);
            }
        }
        return this;
    }

    private void doInvoke() {
        if (!isActive()) {
            throw new IllegalStateException("Hazelcast instance is not active!");
        }
        invokeCount++;
        final Address target = getTarget();
        final Address thisAddress = nodeEngine.getThisAddress();
        op.setNodeEngine(nodeEngine).setServiceName(serviceName).setCaller(thisAddress)
                .setPartitionId(partitionId).setReplicaIndex(replicaIndex);
        if (target == null) {
            if (isActive()) {
                setResult(new WrongTargetException(thisAddress, target, partitionId, op.getClass().getName(), serviceName));
            } else {
                setResult(new IllegalStateException("Hazelcast instance is not active!"));
            }
        } else if (!isJoinOperation(op) && nodeEngine.getClusterService().getMember(target) == null) {
            setResult(new TargetNotMemberException(target, partitionId, op.getClass().getName(), serviceName));
        } else {
            OperationAccessor.setInvocationTime(op, nodeEngine.getClusterTime());
            final OperationServiceImpl operationService = nodeEngine.operationService;
            if (thisAddress.equals(target)) {
                ResponseHandlerFactory.setLocalResponseHandler(this);
                operationService.runOperation(op);
            } else {
                Call call = new Call(target, this);
                final long callId = operationService.registerCall(call);
                OperationAccessor.setCallId(op, callId);
                boolean sent = operationService.send(op, target);
                if (!sent) {
                    setResult(new RetryableException(new IOException("Packet not sent!")));
                }
            }
        }
    }

    private boolean isActive() {
        return nodeEngine.getNode().isActive();
    }

    final void setResult(Object obj) {
        if (obj == null) {
            obj = NULL_RESPONSE;
        }
        responseQ.offer(obj);
    }

    public Object get() throws InterruptedException, ExecutionException {
        try {
            return doGet(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            nodeEngine.getLogger(getClass().getName()).log(Level.FINEST, e.getMessage(), e);
            return null;
        }
    }

    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return doGet(timeout, unit);
    }

    // TODO: rethink about interruption and timeouts
    private Object doGet(long time, TimeUnit unit) throws ExecutionException, TimeoutException, InterruptedException {
        long timeout = unit.toMillis(time);
        if (timeout < 0) timeout = 0;

        final long maxCallTimeout = callTimeout * 2 > 0 ? callTimeout * 2 : Long.MAX_VALUE;
        final boolean longPolling = timeout > maxCallTimeout;
        int pollCount = 0;

        while (timeout >= 0) {
            final long pollTimeout = Math.min(maxCallTimeout, timeout);
            final long start = Clock.currentTimeMillis();
            final Object response;
            try {
                response = responseQ.poll(pollTimeout, TimeUnit.MILLISECONDS);
                timeout = decrementTimeout(timeout, Clock.currentTimeMillis() - start);
            } catch (InterruptedException e) {
                // do not allow interruption while waiting for a response!
                logger.log(Level.FINEST, Thread.currentThread().getName()  + " is interrupted while waiting " +
                        "response for operation " + op);
                if (!isActive()) {
                    throw e;
                }
                continue;
            }
            pollCount++;

            if (response instanceof RetryableException) {
                final int localInvokeCount = invokeCount;
                if (localInvokeCount < tryCount && timeout > 0) {
                    Thread.sleep(tryPauseMillis);
                    timeout = decrementTimeout(timeout, tryPauseMillis);
                    // TODO: improve logging (see SystemLogService)
                    if (localInvokeCount > 5 && localInvokeCount % 10 == 0) {
                        logger.log(Level.WARNING, "Still invoking: " + toString());
                    }
                    doInvoke();
                } else {
                    done = true;
                    throw new ExecutionException((Throwable) response);
                }
            } else if (response == NULL_RESPONSE) {
                return null;
            } else if (response != null) {
                done = true;
                if (response instanceof Throwable) {
                    if (response instanceof RuntimeException) {
                        throw (RuntimeException) response;
                    }
                    if (response instanceof Error) {
                        throw (Error) response;
                    }
                    if (response instanceof ExecutionException) {
                        throw (ExecutionException) response;
                    }
                    throw new ExecutionException((Throwable) response);
                } else {
                    return response;
                }
            } else if (/* response == null && */ longPolling) {
                // no response!
                final Address target = getTarget();
                if (nodeEngine.getThisAddress().equals(target)) {
                    // target may change during invocation because of migration!
                   continue;
                }
                // TODO: improve logging (see SystemLogService)
                logger.log(Level.WARNING, "No response for " + pollTimeout + " ms. " + toString());

                boolean executing = isOperationExecuting(target);
                if (!executing) {
                    Object obj = responseQ.poll(); // real response might arrive before "is-executing" response.
                    if (obj != null) {
                        return obj;
                    }
                    throw new OperationTimeoutException("No response for " + (pollTimeout * pollCount)
                            + " ms. Aborting invocation! " + toString());
                }
            }
        }
        throw new TimeoutException();
    }

    private boolean isOperationExecuting(Address target) {
        // ask if op is still being executed?
        Boolean executing = Boolean.FALSE;
        try {
            final InvocationImpl inv = new TargetInvocationImpl(nodeEngine, serviceName,
                    new IsStillExecuting(op.getCallId()), target, 0, 0, 5000);
            inv.invoke();
            // TODO: improve logging (see SystemLogService)
            logger.log(Level.WARNING, "Asking if operation execution has been started: " + toString());
            executing = (Boolean) nodeEngine.toObject(inv.get(5000, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            logger.log(Level.WARNING, "While asking 'is-executing': " + toString(), e);
        }
        // TODO: improve logging (see SystemLogService)
        logger.log(Level.WARNING, "'is-executing': " + executing + " -> " + toString());
        return executing;
    }

    private static long decrementTimeout(long timeout, long diff) {
        if (timeout != Long.MAX_VALUE) {
            timeout -= diff;
        }
        return timeout;
    }

    // TODO: works only for parent-child invocations; multiple chained invocations can break the rule!
    private static void checkOperationType(Operation op) {
//        final Operation parentOp = (Operation) ThreadContext.get().getCurrentOperation();
//        boolean allowed = true;
//        if (parentOp != null) {
//            if (op instanceof BackupOperation && !(parentOp instanceof BackupOperation)) {
//                // OK!
//            } else if (parentOp instanceof PartitionLevelOperation) {
//                if (op instanceof PartitionLevelOperation
//                        && op.getPartitionId() == parentOp.getPartitionId()) {
//                    // OK!
//                } else if (!(op instanceof PartitionAwareOperation)) {
//                    // OK!
//                } else {
//                    allowed = false;
//                }
//            } else if (parentOp instanceof KeyBasedOperation) {
//                if (op instanceof PartitionLevelOperation) {
//                    allowed = false;
//                } else if (op instanceof KeyBasedOperation
//                        && ((KeyBasedOperation) parentOp).getKeyHash() == ((KeyBasedOperation) op).getKeyHash()
//                        && parentOp.getPartitionId() == op.getPartitionId()) {
//                    // OK!
//                } else if (op instanceof PartitionAwareOperation
//                        && op.getPartitionId() == parentOp.getPartitionId()) {
//                    // OK!
//                } else if (!(op instanceof PartitionAwareOperation)) {
//                    // OK!
//                } else {
//                    allowed = false;
//                }
//            } else if (parentOp instanceof PartitionAwareOperation) {
//                if (op instanceof PartitionLevelOperation) {
//                    allowed = false;
//                } else if (op instanceof PartitionAwareOperation
//                        && op.getPartitionId() == parentOp.getPartitionId()) {
//                    // OK!
//                } else if (!(op instanceof PartitionAwareOperation)) {
//                    // OK!
//                } else {
//                    allowed = false;
//                }
//            }
//        }
//        if (!allowed) {
//            throw new HazelcastException("INVOCATION IS NOT ALLOWED! ParentOp: "
//                    + parentOp + ", CurrentOp: " + op);
//        }
    }

    public String getServiceName() {
        return serviceName;
    }

    public Operation getOperation() {
        return op;
    }

    public PartitionInfo getPartitionInfo() {
        return nodeEngine.getPartitionInfo(partitionId);
    }

    public int getReplicaIndex() {
        return replicaIndex;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException();
    }

    public boolean isCancelled() {
        return false;
    }

    public int getPartitionId() {
        return partitionId;
    }

    public boolean isDone() {
        return done;
    }

    @Override
    public String toString() {
        return "InvocationImpl{" +
                "serviceName='" + serviceName + '\'' +
                ", op=" + op +
                ", partitionId=" + partitionId +
                ", replicaIndex=" + replicaIndex +
                ", invokeCount=" + invokeCount +
                ", tryCount=" + tryCount +
                ", callTimeout=" + callTimeout +
                '}';
    }

    public static class IsStillExecuting extends AbstractOperation {

        private long operationCallId;

        IsStillExecuting() {
        }

        private IsStillExecuting(long operationCallId) {
            this.operationCallId = operationCallId;
        }

        public void run() throws Exception {
            NodeEngineImpl nodeEngine = (NodeEngineImpl) getNodeEngine();
            OperationServiceImpl operationService = nodeEngine.operationService;
            boolean executing = operationService.isOperationExecuting(getCaller(), operationCallId);
            getResponseHandler().sendResponse(executing);
        }

        @Override
        public boolean returnsResponse() {
            return false;
        }

        @Override
        protected void readInternal(ObjectDataInput in) throws IOException {
            super.readInternal(in);
            operationCallId = in.readLong();
        }

        @Override
        protected void writeInternal(ObjectDataOutput out) throws IOException {
            super.writeInternal(out);
            out.writeLong(operationCallId);
        }
    }

    private static final ClassLoader thisClassLoader = InvocationImpl.class.getClassLoader();

    private static boolean isJoinOperation(Operation op) {
        return op instanceof JoinOperation
                && op.getClass().getClassLoader() == thisClassLoader;
    }
}
