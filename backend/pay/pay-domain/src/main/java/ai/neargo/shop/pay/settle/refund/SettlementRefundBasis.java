package ai.neargo.shop.pay.settle.refund;

/** 原始金额与历史账户的不可变版本；未知旧值保留null，不能当成零参与自动切分。 */
public record SettlementRefundBasis(String settleNo, String subOrderNo, int basisVersion,
                                    String source, String status, String reviewReason,
                                    Long goodsPaidMinor, Long grossMinor, Long merchantGoodsMinor,
                                    Long commissionMinor, Long serviceFeeMinor, Long pointsFeeMinor,
                                    Long pointsCostMinor, Long subsidyMinor, Long freightIncomeMinor,
                                    Long freightCostMinor, String freightShipMode, String paymentRoute,
                                    String originPaymentNo, String accountSnapshotRef,
                                    SettlementAccountSnapshot accountSnapshot) {
    public static final String READY = "READY";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    /** 自动退款必须先确认原值可信；待核对的行也可以被查询和展示。 */
    public boolean ready() { return READY.equals(status); }
}
