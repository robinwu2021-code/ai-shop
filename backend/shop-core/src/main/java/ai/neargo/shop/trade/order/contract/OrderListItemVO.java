package ai.neargo.shop.trade.order.contract;

/** 订单列表项：一行一订单（主单粒度），{@code packageCount} 替代现状 payGroupSize。 */
public record OrderListItemVO(
        String orderNo,
        String status,
        Long payAmount,
        int packageCount,
        String title,
        String cover,
        int itemCount) {
}
