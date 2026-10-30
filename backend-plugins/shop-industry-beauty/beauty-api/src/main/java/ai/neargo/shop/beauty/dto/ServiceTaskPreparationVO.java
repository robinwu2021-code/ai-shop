package ai.neargo.shop.beauty.dto;

import java.time.Instant;

/** Safe progress projection deliberately excludes proof bytes and saved human provenance. */
public record ServiceTaskPreparationVO(
        String operationNo,
        String submissionNo,
        String workOrderNo,
        String taskNo,
        String executionNo,
        String preparationState,
        String quantityState,
        String stockState,
        String pendingReason,
        String recoveryAction,
        Instant observedAt,
        boolean canStart) {
    public ServiceTaskPreparationVO {
        if (canStart || !operationNo.equals(submissionNo))
            throw new IllegalArgumentException("invalid preparation");
    }

    public ServiceTaskPreparationVO(
            String submissionNo,
            String workOrderNo,
            String taskNo,
            String executionNo,
            String preparationState,
            String quantityState,
            String stockState,
            String pendingReason,
            String recoveryAction,
            Instant observedAt) {
        this(
                submissionNo,
                submissionNo,
                workOrderNo,
                taskNo,
                executionNo,
                preparationState,
                quantityState,
                stockState,
                pendingReason,
                recoveryAction,
                observedAt,
                false);
    }
}
