package ai.neargo.shop.spi.trade;

import java.util.List;

/**
 * settle → trade：生成结算单需要的子单金额。
 *
 * <p>**把 `discountPlatform` 单独给出来**，是因为结算要把平台补贴补回给商家 ——
 * 只给「用户实付」的话，商家会因为平台发的券而少收钱，这是最伤商家信任的一类错误。
 */
public interface SettleSourcePort {

    List<SettleSource> settleSourcesOf(String orderNo);

    /**
     * @param payAmount        用户实付（已扣所有优惠）
     * @param discountPlatform 平台出资的优惠 —— 结算时补回给商家
     * @param discountMerchant 商家出资的优惠 —— 商家自己让的利，不补
     */
    /**
     * @param pickupNo  履约自提点。为空表示不经自提点（快递 / 上门），此时没有履约服务费
     * @param itemCount 本子单的**件数**（含赠品）—— 按件计费的自提点靠它算钱。
     *                  分拣与保管的工作量与件数成正比，与金额无关
     * @param storeNo   这笔钱是**哪家店**挣的。结算把它落成快照，用于门店经营报表。
     *                  **它不决定钱打给谁** —— 打给谁看收款号（同一主体的两家店可以
     *                  共用一个收款号，那就是合并结算）。两件事混起来的后果是
     *                  「按店统计」和「按账户打款」互相绑架，而它们本来互不相干。
     *                  为空表示存量主体级子单
     */
    /**
     * @param pointsDeductMinor 买家用积分抵掉的金额（分）。
     *                          <b>payAmount 已经把它扣掉了</b>，而商家按订单全额收款 ——
     *                          所以结算基数要把它加回来，并由平台补差进二级商户。
     *                          不加回来的话，积分的成本就从商家的货款里出了。
     */
    /**
     * @param pointsFeeMinor 本单**发分**要向商家收的费用金（分）。发放时算定，结算时扣。
     *                       <p>与 {@code pointsDeductMinor} 是<b>反方向的两笔</b>，别混：
     *                       抵扣是平台<b>付给</b>收单商家的钱（出池），
     *                       费用金是平台<b>向发放商家收的</b>钱（入池）。
     *                       同一张单上两笔都可能有，而且收付双方通常不是同一家。
     */
    record SettleSource(String subOrderNo, String merchantNo, String trafficSource,
                        long payAmount, long discountPlatform, long discountMerchant,
                        String pickupNo, int itemCount, String storeNo,
                        long pointsDeductMinor, long pointsFeeMinor) {
    }
}
