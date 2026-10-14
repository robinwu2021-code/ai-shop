package ai.neargo.shop.trade.order.command;

import java.util.List;

/**
 * 下单命令（订单服务 2.0 写路径）。字段对齐现有 {@code CreateOrderReq.toCommand}。
 * <b>本阶段仅定义，不实现</b>（写路径实现依赖领域假设定稿 + 发货单层迁移）。
 */
public record CreateOrderCommand(
        String userNo,
        List<Line> items,
        String fulfillment,
        String pickupNo,
        String addressId,
        String couponNo,
        Long usePoints,
        String payMode,
        Long appointmentAt,
        String remark) {

    /** 一个 SKU 一行。 */
    public record Line(String skuNo, int qty) {
    }
}
