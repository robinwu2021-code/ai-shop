package ai.neargo.shop.trade.order.command;

/** 发货结果：新建的发货单号 + 该订单发货后的聚合状态。本阶段仅定义。 */
public record ShipResult(
        String orderNo,
        String shipmentId,
        String status) {
}
