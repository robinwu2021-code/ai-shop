package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;

/**
 * 结算口径：一笔退款在结算单上各项金额里占多少。
 *
 * <p>规则对象（接口 + 默认实现），将来按商家 / 经营模式换口径只换实现。
 */
public interface SettlementRefundPolicy {

    /**
     * @param bill               结算单（读其各项金额与已退货款累计）
     * @param refundGoodsMinor   本次退的货款
     * @param refundFreightMinor 本次退的运费
     * @param goodsPaidMinor     该子单商品实付总额（实付 − 运费），比例的分母
     */
    BillRefundShare share(StlBill bill, long refundGoodsMinor, long refundFreightMinor, long goodsPaidMinor);
}
