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
     * 这些子单<b>可结算了没有</b>：履约完成时刻，以及售后有没有闭环。
     *
     * <p><b>由结算域点名问，而不是让它去扫订单表</b>：谁还没定 T2，
     * 结算域自己最清楚（{@code settleable_at} 为空的那些），
     * 而「这单什么时候履约完的」「售后闭没闭环」只有交易域答得上。
     * 反过来做（结算域扫 {@code ord_*}）会被架构守卫拦下，
     * 而且会把「哪些单该算」这个判断复制到两个域里。
     *
     * <p>返回里<b>不含</b>查不到的子单 —— 调用方据此知道「这单还没完成」，
     * 而不是收到一个含糊的 0。
     *
     * @param subOrderNos 点名要问的子单；空集合返回空列表
     */
    List<SettleReadiness> settleReadiness(java.util.Collection<String> subOrderNos);

    /**
     * @param completedAt   履约完成时刻（毫秒）。<b>取状态流水里进 COMPLETED 那一刻</b>，
     *                      不是子单的更新时间 —— 后者会被任何一次无关改动推后
     * @param afterSaleOpen 有没有<b>未闭环</b>的售后。为 true 时这单不进批：
     *                      售后没闭环就解冻，等于把争议中的钱先给了一方
     */
    record SettleReadiness(String subOrderNo, long completedAt, boolean afterSaleOpen) {
    }

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
    /**
     * @param payChannel 支付通道 WECHAT / ALIPAY / <b>OFFLINE</b>（{@code PayModes.OFFLINE}）。
     *                   <b>取自主单，不是子单</b> —— 一次支付覆盖整张订单，跨商家合单时
     *                   几家用的是同一个通道。
     *                   <p>它此前<b>从没传进结算域</b>：{@code stl_bill.pay_channel} 全库为 null，
     *                   而 generateForOrder 里已经有一处在读它（入池流水的通道字段）。
     *                   线下单靠它认出来，所以顺带补上。
     * @param payScene   下单端 MP_WECHAT / IOS / …（{@code PayScenes}）。与通道不是一回事：
     *                   App 两个通道都能走。报表按端切分要用它
     */
    record SettleSource(String subOrderNo, String merchantNo, String trafficSource,
                        long payAmount, long discountPlatform, long discountMerchant,
                        String pickupNo, int itemCount, String storeNo,
                        long pointsDeductMinor, long pointsFeeMinor,
                        String payChannel, String payScene) {
    }
}
