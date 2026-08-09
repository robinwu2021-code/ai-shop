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

    record SkuQty(String skuNo, int qty) {
    }
}
