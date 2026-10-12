package ai.neargo.shop.trade.order.contract;

import java.util.List;

/**
 * 统一订单视图（买家对外）。{@code orderNo}=主单号；{@code status}=聚合契约状态；
 * {@code packages}=发货分组（内部子单）。消除现状 payView/orderView 两视角二义。
 */
public record UnifiedOrderVO(
        String orderNo,
        String status,
        Long payAmount,
        Long paidAt,
        int packageCount,
        List<OrderPackageVO> packages) {
}
