package ai.neargo.shop.spi.trade;

import java.util.Objects;

/** Quantity authority is separate from a human start, resource occupation and payment. */
public interface ServiceExecutionPort {
    /** Current authorized preparation reserves one original unit; it cannot start service. */
    Grant prepare(PrepareExecution request);

    /** Human reads retain current scoped access checks in the actual owner. */
    Grant query(ExecutionLookup request);

    /** Machine recovery reads one persisted original without installing a saved human identity. */
    Grant queryOriginal(ExecutionLookup request);

    // M4 markStarted/releaseUnstarted and their resource proof types remain staged.
    // M6 fulfill/completion types remain staged until the actual completion owner is implemented.

    /** Scope carries original owner coordinates, never a caller-selected authorization grant. */
    record ExecutionScope(String tenantNo, String entityNo, String storeNo) {
        public ExecutionScope {
            ServiceExecutionContract.exact(tenantNo, "MAIN");
            ServiceExecutionContract.reference(entityNo, 32);
            ServiceExecutionContract.reference(storeNo, 32);
        }
    }

    /** Frozen coordinates survive retries, changed observations and terminal unit states. */
    record ExecutionBinding(
            ExecutionScope scope, String caller, String sourceOperationNo, String orderNo, String subOrderNo,
            String sourceLineNo, int unitOrdinal, String workOrderNo, long scopeRevision, String taskNo,
            long taskRevision, String bindingNo, long bindingRevision, String executionNo, int attemptNo,
            String submissionNo, String sourceFenceNo, long sourceFenceRevision, long sourceEpoch,
            long sourceRevision, String sourceHash, String definitionVersionNo, String executionSnapshotHash,
            String planHash, String payloadHash) {
        public ExecutionBinding {
            Objects.requireNonNull(scope, "scope");
            ServiceExecutionContract.exact(caller, "BEAUTY");
            ServiceExecutionContract.reference(sourceOperationNo, 64);
            ServiceExecutionContract.reference(orderNo, 64);
            ServiceExecutionContract.reference(subOrderNo, 64);
            ServiceExecutionContract.reference(sourceLineNo, 40);
            if (unitOrdinal < 1 || unitOrdinal > 999 || attemptNo < 1) {
                throw new IllegalArgumentException("invalid original execution unit");
            }
            ServiceExecutionContract.reference(workOrderNo, 32);
            ServiceExecutionContract.positive(scopeRevision);
            ServiceExecutionContract.reference(taskNo, 32);
            ServiceExecutionContract.nonnegative(taskRevision);
            ServiceExecutionContract.reference(bindingNo, 32);
            ServiceExecutionContract.positive(bindingRevision);
            ServiceExecutionContract.reference(executionNo, 32);
            ServiceExecutionContract.reference(submissionNo, 32);
            ServiceExecutionContract.reference(sourceFenceNo, 32);
            ServiceExecutionContract.positive(sourceFenceRevision);
            ServiceExecutionContract.positive(sourceEpoch);
            ServiceExecutionContract.positive(sourceRevision);
            ServiceExecutionContract.hash(sourceHash);
            ServiceExecutionContract.reference(definitionVersionNo, 32);
            ServiceExecutionContract.hash(executionSnapshotHash);
            ServiceExecutionContract.hash(planHash);
            ServiceExecutionContract.hash(payloadHash);
        }

        @Override
        public String toString() {
            return "ExecutionBinding[REDACTED]";
        }
    }

    /** Lookup cannot replace the original with a target number alone. */
    record ExecutionLookup(ExecutionBinding binding) {
        public ExecutionLookup {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /** Both the permanent unit binding and the original action key are required for idempotency. */
    record PrepareExecution(ExecutionBinding binding, String actionNo, String requestKeyHash, String actionHash) {
        public PrepareExecution {
            Objects.requireNonNull(binding, "binding");
            ServiceExecutionContract.reference(actionNo, 32);
            ServiceExecutionContract.hash(requestKeyHash);
            ServiceExecutionContract.hash(actionHash);
        }
    }

    /** Durable grant state does not imply actual participation or a successful resource effect. */
    enum GrantState {
        PREPARED, SUSPEND_REQUESTED, STARTED, STARTED_SUSPENDED, RELEASING, RELEASED, FULFILLED
    }

    /** Internal bounded causes use existing transport error codes at their eventual adapters. */
    enum Reason {
        SOURCE_UNKNOWN, SOURCE_SUSPENDED, SOURCE_REVISION_CONFLICT, SOURCE_IDENTITY_MISMATCH,
        SOURCE_QUANTITY_EXCEEDED, TASK_CHANGED, PERMANENT_UNIT_CONFLICT, RESOURCE_PLAN_REQUIRED,
        UNSUPPORTED_TASK_QUANTITY, RESOURCE_EXECUTION_UNAVAILABLE, EXECUTION_SCHEMA_UNAVAILABLE
    }

    /** Count disjoint permanent unit buckets rather than counting attempts or retry actions. */
    record QuantityFacts(int orderedUnits, int reservedUnits, int performedUnits, int voidedUnits) {
        public QuantityFacts {
            if (orderedUnits < 1 || orderedUnits > 999 || reservedUnits < 0 || performedUnits < 0
                    || voidedUnits < 0 || (long) reservedUnits + performedUnits + voidedUnits > orderedUnits) {
                throw new IllegalArgumentException("invalid disjoint execution quantities");
            }
        }
    }

    /** Missing originals are errors in the owner; no nullable grant can fabricate availability. */
    record Grant(ExecutionBinding binding, String grantNo, long grantRevision, GrantState state,
            long observationEpoch, QuantityFacts quantity, String reasonCode) {
        public Grant {
            Objects.requireNonNull(binding, "binding");
            ServiceExecutionContract.reference(grantNo, 32);
            ServiceExecutionContract.positive(grantRevision);
            Objects.requireNonNull(state, "state");
            ServiceExecutionContract.nonnegative(observationEpoch);
            Objects.requireNonNull(quantity, "quantity");
            if (binding.unitOrdinal() > quantity.orderedUnits()) {
                throw new IllegalArgumentException("ordinal outside original quantity");
            }
            ServiceExecutionContract.reason(reasonCode, false);
        }
    }
}
