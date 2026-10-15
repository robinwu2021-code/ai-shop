package ai.neargo.shop.pay.settle.refund;

import java.util.Map;

/**
 * 一次退款落在结算单上的份额（结算口径 A 算出来的结果，不可变）。
 *
 * <p>分账后只用到 {@link #merchantReverseMinor()} 与 {@link #subsidyReturnMinor()}（发给通道的两笔）；
 * 分账前五项 *Cut 一起从结算单上扣掉，后续分账按扣减后的金额走。
 *
 * @param goodsMinor          本次退的货款（买家实付口径，不含运费）
 * @param freightMinor        本次退的运费
 * @param merchantGoodsMinor  商家货款所得中本次要回退的部分
 * @param freightReverseMinor 运费中由商家回退的部分（商家自寄才有；平台代寄由平台承担）
 * @param subsidyReturnMinor  积分补差中本次收回的部分
 * @param grossCutMinor       结算基数扣减
 * @param commissionCutMinor  佣金扣减（平台不对退掉的货收佣）
 * @param serviceFeeCutMinor  履约服务费扣减
 * @param pointsFeeCutMinor   积分服务费扣减
 * @param pointsCostCutMinor  归集路径下积分成本扣减
 * @param finalRefund         本次之后商品现金已全部分配；财务结清由 FinancialClosure 单独判断
 */
public record BillRefundShare(long goodsMinor, long freightMinor,
                              long merchantGoodsMinor, long freightReverseMinor, long subsidyReturnMinor,
                              long grossCutMinor, long commissionCutMinor, long serviceFeeCutMinor,
                              long pointsFeeCutMinor, long pointsCostCutMinor, boolean finalRefund,
                              CashAllocation cashAllocation, boolean requiresReview) {

    public enum CashAllocation { NONE, PROPORTIONAL }

    /** 商家本次要退回的所得合计 —— 分账后就是发给通道的回退金额。 */
    public long merchantReverseMinor() {
        return merchantGoodsMinor + freightReverseMinor;
    }

    /** 平台少收的部分（佣金 + 服务费 + 积分服务费），即分账额的扣减。 */
    public long platformCutMinor() {
        return commissionCutMinor + serviceFeeCutMinor + pointsFeeCutMinor;
    }

    /** 分账前扣减结算单各列用的参数（键名与 {@code BillRefundMapper#adjustBeforeSplit} 一一对应）。 */
    public Map<String, Long> billCuts() {
        return Map.of("goods", goodsMinor, "gross", grossCutMinor, "commission", commissionCutMinor,
                "serviceFee", serviceFeeCutMinor, "pointsFee", pointsFeeCutMinor, "subsidy", subsidyReturnMinor,
                "pointsCost", pointsCostCutMinor, "freight", freightReverseMinor,
                "platform", platformCutMinor(), "merchant", merchantReverseMinor());
    }
}
