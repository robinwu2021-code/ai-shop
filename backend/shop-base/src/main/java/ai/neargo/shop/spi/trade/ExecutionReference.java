package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.util.List;
import java.util.Objects;

/** Exact-original capability minted by Core; planned command identifies BTRC, never an actual RBC journal. */
public record ExecutionReference(String schema, ExecutionScope scope, SourceKind sourceKind, String bindingSchema,
        String executionNo, String grantNo, String unitNo, String taskNo, String workOrderNo, int attemptNo,
        long preparedGrantRevision, String bindingHash, String payloadHash, String originalBindingBytesHash,
        String sourceIdentityHash, String sourceIntentHash, String sourceFenceNo, long sourceFenceRevision,
        String planHash, String plannedResourceCommandNo, String plannedResourceCommandHash,
        String prepareActionNo, String prepareActionHash, String prepareKeyHash, String referenceHash) {
    public enum SourceKind {
        STORE_ORDER
    }

    public ExecutionReference {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sourceKind, "sourceKind");
        ServiceExecutionContract.exact(schema, "SERVICE_EXECUTION_REFERENCE_V1");
        ServiceExecutionContract.exact(bindingSchema, "MANUAL_EXECUTION_V1");
        for (String value : List.of(executionNo, grantNo, unitNo, taskNo, workOrderNo, sourceFenceNo,
                plannedResourceCommandNo, prepareActionNo)) ServiceExecutionContract.reference(value, 32);
        for (String value : List.of(bindingHash, payloadHash, originalBindingBytesHash, sourceIdentityHash,
                sourceIntentHash, planHash, plannedResourceCommandHash, prepareActionHash, prepareKeyHash)) {
            ServiceExecutionContract.hash(value);
        }
        ServiceExecutionContract.positive(attemptNo);
        ServiceExecutionContract.positive(preparedGrantRevision);
        ServiceExecutionContract.positive(sourceFenceRevision);
        if (referenceHash != null) ServiceExecutionContract.hash(referenceHash);
    }

    /** Factory completion computes the full reference digest, never accepts a caller's shortened target. */
    public ExecutionReference signed() {
        return new ExecutionReference(schema, scope, sourceKind, bindingSchema, executionNo, grantNo, unitNo,
                taskNo, workOrderNo, attemptNo, preparedGrantRevision, bindingHash, payloadHash,
                originalBindingBytesHash, sourceIdentityHash, sourceIntentHash, sourceFenceNo, sourceFenceRevision,
                planHash, plannedResourceCommandNo, plannedResourceCommandHash, prepareActionNo, prepareActionHash,
                prepareKeyHash, ExecutionEvidenceContract.digest("SERVICE_EXECUTION_REFERENCE_V1", this,
                    "referenceHash"));
    }

    /** Consumers validate before any owner lookup or mutation. */
    public void validate() {
        ExecutionEvidenceContract.require(equals(signed()));
    }
}
