package ai.neargo.shop.pay.settle.refund;

/**
 * 结算口径：一笔退款在结算单上各项金额里占多少。
 *
 * <p>规则对象（接口 + 默认实现），将来按商家 / 经营模式换口径只换实现。
 */
public interface SettlementRefundPolicy {

    /**
     * @param basis              不可变原始金额，不能从当前应付反推
     * @param before             已应用货款/运费前值，由同一结算事务读取并以CAS提交
     * @param refundGoodsMinor   本次退的货款
     * @param refundFreightMinor 本次退的运费
     */
    BillRefundShare share(SettlementRefundBasis basis, RefundPosition before,
                          long refundGoodsMinor, long refundFreightMinor);
}
