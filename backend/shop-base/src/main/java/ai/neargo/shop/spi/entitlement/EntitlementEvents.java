package ai.neargo.shop.spi.entitlement;

/** Allowlisted entitlement events shared by the pay owner and core consumers. */
public final class EntitlementEvents {
    private EntitlementEvents() { }

    public record EntitlementBatchPrepared(String eventNo, String tenantNo, String batchNo, String entityNo,
                                           String actorNo, long occurredAt) implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "ENTITLEMENT_ISSUE_BATCH"; }
        @Override public String aggregateId() { return batchNo; }
        @Override public String eventType() { return "ENTITLEMENT_BATCH_PREPARED"; }
    }

    public record EntitlementGranted(String eventNo, String grantNo, String userNo, String entityNo,
                                     String templateVersionNo, String sourceType, String sourceNo,
                                     long occurredAt) implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "ENTITLEMENT_GRANT"; }
        @Override public String aggregateId() { return grantNo; }
        @Override public String eventType() { return "ENTITLEMENT_GRANTED"; }
    }

    public record EntitlementUsed(String eventNo, String usageNo, String userNo, String entityNo, String storeNo,
                                  String assetKind, String assetNo, Long amountMinor, Integer units,
                                  long occurredAt) implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "ENTITLEMENT_USAGE"; }
        @Override public String aggregateId() { return usageNo; }
        @Override public String eventType() { return "ENTITLEMENT_USED"; }
    }

    public record EntitlementUseReversed(String eventNo, String reverseNo, String usageNo, String userNo,
                                          String entityNo, String storeNo, String instanceNo, int restoredUnits,
                                          long discountMinor, String currency, long occurredAt)
            implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "ENTITLEMENT_REVERSAL"; }
        @Override public String aggregateId() { return reverseNo; }
        @Override public String eventType() { return "ENTITLEMENT_USE_REVERSED"; }
    }

    public record StoredValueCredited(String eventNo, String rechargeNo, String accountNo, String userNo,
                                      String entityNo, String storeNo, long paidAmountMinor,
                                      long creditedAmountMinor, String currency, long occurredAt)
            implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "STORED_VALUE_RECHARGE"; }
        @Override public String aggregateId() { return rechargeNo; }
        @Override public String eventType() { return "STORED_VALUE_CREDITED"; }
    }
}
