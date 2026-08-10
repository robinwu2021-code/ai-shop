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
