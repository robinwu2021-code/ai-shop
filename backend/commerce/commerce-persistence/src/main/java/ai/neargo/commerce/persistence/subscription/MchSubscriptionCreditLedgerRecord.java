package ai.neargo.commerce.persistence.subscription;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MchSubscriptionCreditLedgerRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String ledgerNo,
    String storeNo,
    String currency,
    String creditLotNo,
    String eventType,
    String bizRefNo,
    Integer eventSeq,
    Long amountMinor,
    Long balanceDeltaMinor,
    Long reservedDeltaMinor,
    String orderComponentNo,
    String reversalOfLedgerNo,
    String reason) {}
