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
     * 这段时间里<b>已支付</b>的子单（不变式 I1 的左边）。
     *
     * <p>结算域拿它去比「每个 PAID 子单都有 stl_bill 吗」。
     * <b>这是唯一一条不依赖消息、只看事实的检查</b> ——
     * Outbox 投递失败、消费者有 bug、甚至 Outbox 那一行本身没写成功，
     * 都躲不过它。
     *
     * @param since 起始时刻（毫秒）
     * @param limit 单轮上限
     */
    List<PaidSubOrder> paidSubOrdersSince(long since, int limit);

    /**
     * 这批子单里<b>不是已支付状态</b>的（不变式 I2 的右边）。
     *
     * <p>结算域拿它去比「每张 stl_bill 都对得上一个已支付子单吗」。
     * 对不上的<b>只告警不自动删</b> —— 删账是不可逆动作。
     *
     * <p>返回的是「异常的那些」而不是「正常的那些」：直接返回差集，
     * 调用方不用再做一次减法 —— 那次减法写反的话，报出来的会是完全相反的一批单。
     */
    List<String> notPaidAmong(java.util.Collection<String> subOrderNos);

    /**
     * @param subOrderNo 子单号
     * @param orderNo    主单号 —— 补生成结算单是按主单做的（{@code generateForOrder}）
     * @param paidAt     支付时刻，用来算「漏了多久」
     */
    record PaidSubOrder(String subOrderNo, String orderNo, long paidAt) {
    }

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
