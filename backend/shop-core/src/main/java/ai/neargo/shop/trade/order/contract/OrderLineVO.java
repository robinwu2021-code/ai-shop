package ai.neargo.shop.trade.order.contract;

/** 订单商品行（展示用）。 */
public record OrderLineVO(
        String skuNo,
        String title,
        String cover,
        String spec,
        long price,
        int qty,
        long amount) {
}
