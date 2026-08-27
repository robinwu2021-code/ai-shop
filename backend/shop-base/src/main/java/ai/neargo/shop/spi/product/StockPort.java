package ai.neargo.shop.spi.product;

import java.util.List;

/**
 * trade → product：库存锁定与释放。
 *
 * <p>为什么锁定/释放/扣减三个动作都在 product 而不是 trade：库存是商品域的状态，
 * 交易域只是它的一个使用者。放到 trade 里，将来「后台调整库存」「盘点」这些非交易场景
 * 就要反向依赖交易模块。
 */
public interface StockPort {

    /**
     * 锁定库存（下单时）。全部成功或全部失败，不允许部分锁定 ——
     * 部分锁定意味着用户看到「购物车里 3 件商品成功了 2 件」，这种订单没法结算也没法退。
     *
     * @param lockNo 锁定单号（= 订单号），释放与确认都用它
     * @return 库存不足的 SKU；空列表表示全部锁定成功
     */
    List<String> lock(String lockNo, List<SkuQty> items);

    /** 释放（超时未支付、下单失败回滚）。幂等：重复释放不会把库存加两次。 */
    void release(String lockNo);

    /** 确认扣减（支付成功）。锁定转实扣，此后不再释放。 */
    void confirm(String lockNo);

    /**
     * 退货入库：把货加回来。
     *
     * <p><b>触发判据是售后类型，不是「退款成功」</b> —— 这条判断留在调用方（trade），
     * 因为只有它认得 {@code ord_after_sale.type}：
     * {@code REFUND_ONLY} 不回补（货根本没回来）、{@code RETURN_REFUND} 回补、
     * {@code EXCHANGE} 一出一入（平台侧没有流水，净变动为零，这一期不动）。
     *
     * <p><b>为什么这条以前不存在</b>：{@code doRefund} 的注释写着「发事件，下游据此回补库存」，
     * 事件也确实发了 —— 但商品域一个消费者都没有。表现是**退货退款之后库存从不回补**：
     * 货回到店里了，而库里当它卖掉了，于是这一件会被再卖一次，
     * 且要等到发货那天才发现。
     *
     * <p>幂等由调用方保证（{@code ord_after_sale.stock_restored} 一次性置位）——
     * 这里不做第二道，因为平台侧还没有库存流水；等真相源切到进销存之后，
     * 幂等由流水的唯一键 {@code (doc_no, line_no)} 天然兜住。
     *
     * @param restoreNo 售后单号，仅用于日志与将来的流水单号
     */
    void restore(String restoreNo, List<SkuQty> items);

    /**
     * 把库存<b>设成这个数</b>（商家在商品页手改）。
     *
     * <h2>为什么它要进 Port，而不是商品域自己 update</h2>
     * 商家有两个改库存的入口：商品页的「改库存」与库存页的「改数」。
     * 前者原本直接 {@code updateById} 改 {@code prd_sku.stock}，后者走进销存的盘点单 ——
     * <b>两个都在 B 端、都归商家、都叫「改库存」，却写进两本互不知道的账</b>。
     * 搬运之后同一件货就有了两个数、两个改法，而改任一个另一个都不知道。
     *
     * <p>收进 Port 之后，真相源在哪它就落到哪：{@code PLATFORM} 走原来那条，
     * {@code DUAL} 两边都记，{@code INVENTORY} 落成一张盘点单。
     * <b>入口可以有两个，账只能有一本。</b>
     *
     * @param reason 变更原因（枚举，见 {@code InvEnums.CountReason}）。
     *               <b>不是自由文本</b> —— 自由文本汇总不出「这个月因为什么改了多少」
     */
    void setOnHand(String skuNo, String storeNo, int onHand, String reason);

    /**
     * @param storeNo 这一行在**哪家店**履约，决定扣谁的库存。可空。
     *
     * <p><b>门店放在行上而不是整次调用上</b>：一笔跨商家的订单会拆成多个子单，
     * 各自的履约门店不同，一个参数表达不了。而按商家分成 N 次调用，
     * 会让「一次把所有库存不足的 SKU 都收集齐」这个约定失效 ——
     * 第一个商家不足就抛了，用户改完再提交才发现第二个商家也不足。
     *
     * <p>空值的含义是「这一行按主体总量扣」，与单店时代逐字相同。
     * 真正决定走哪条路的是 SKU 有没有启用分店库存，见 StockPortImpl。
     */
    record SkuQty(String skuNo, int qty, String storeNo) {

        /** 主体级扣减。存量调用方与单店场景用这个 */
        public SkuQty(String skuNo, int qty) {
            this(skuNo, qty, null);
        }
    }
}
