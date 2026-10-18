package ai.neargo.shop.spi.entitlement;

/** Allowlisted entitlement events shared by the pay owner and core consumers. */
public final class EntitlementEvents {
    private EntitlementEvents() { }

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
