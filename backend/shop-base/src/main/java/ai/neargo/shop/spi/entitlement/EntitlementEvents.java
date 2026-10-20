package ai.neargo.shop.spi.entitlement;

/** Allowlisted entitlement events shared by the pay owner and core consumers. */
public final class EntitlementEvents {
    private EntitlementEvents() { }
    public record LegacyCouponIssueCompleted(String eventNo,String tenantNo,String issueNo,String entityNo,String actorNo,long occurredAt) implements PayEntitlementEvent {
        public int schemaVersion() {return 1;}
        public String aggregateType() {return "LEGACY_COUPON_ISSUE";}
        public String aggregateId() {return issueNo;}
        public String eventType() {return "LEGACY_COUPON_ISSUE_COMPLETED";}
    }

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

    /** Legacy checkout has an actual order and may span stores; no synthetic store or cash amount. */
    public record LegacyCouponUsageChanged(String eventNo,String usageNo,String userNo,String entityNo,
                                           String instanceNo,String orderNo,String status,long occurredAt)
            implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "ENTITLEMENT_USAGE"; }
        @Override public String aggregateId() { return usageNo; }
        @Override public String eventType() { return "LEGACY_COUPON_USAGE_CHANGED"; }
    }

    public record StoredValueCredited(String eventNo, String rechargeNo, String accountNo, String userNo,
                                      String entityNo, String storeNo, long paidAmountMinor,
                                      long creditedAmountMinor, String currency, long occurredAt,String customerNo)
            implements PayEntitlementEvent {
        public StoredValueCredited {if(customerNo==null)customerNo=userNo;}
        public StoredValueCredited(String eventNo,String rechargeNo,String accountNo,String userNo,String entityNo,String storeNo,long paidAmountMinor,long creditedAmountMinor,String currency,long occurredAt){this(eventNo,rechargeNo,accountNo,userNo,entityNo,storeNo,paidAmountMinor,creditedAmountMinor,currency,occurredAt,userNo);}
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "STORED_VALUE_RECHARGE"; }
        @Override public String aggregateId() { return rechargeNo; }
        @Override public String eventType() { return "STORED_VALUE_CREDITED"; }
    }
    public record StoredValueRechargeReceived(String eventNo, String tenantNo, String rechargeNo, String entityNo,
                                               long occurredAt) implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "STORED_VALUE_RECHARGE"; }
        @Override public String aggregateId() { return rechargeNo; }
        @Override public String eventType() { return "STORED_VALUE_RECHARGE_RECEIVED"; }
    }
    public record StoredValueRechargeCancelled(String eventNo, String tenantNo, String rechargeNo, String entityNo,
                                                long occurredAt) implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "STORED_VALUE_RECHARGE"; }
        @Override public String aggregateId() { return rechargeNo; }
        @Override public String eventType() { return "STORED_VALUE_RECHARGE_CANCELLED"; }
    }

    public record ExceptionOpened(String eventNo,String exceptionNo,String entityNo,String kind,String bizNo,
                                  long occurredAt) implements PayEntitlementEvent {
        @Override public int schemaVersion() { return 1; }
        @Override public String aggregateType() { return "ENTITLEMENT_EXCEPTION"; }
        @Override public String aggregateId() { return exceptionNo; }
        @Override public String eventType() { return "ENTITLEMENT_EXCEPTION_OPENED"; }
    }

}
