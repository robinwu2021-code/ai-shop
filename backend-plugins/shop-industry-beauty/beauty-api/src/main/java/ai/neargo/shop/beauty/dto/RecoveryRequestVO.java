package ai.neargo.shop.beauty.dto;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record RecoveryRequestVO(
        String recoveryNo,
        String submissionNo,
        String operationNo,
        long acceptedRevision,
        String state,
        String recoveryAction) {}
