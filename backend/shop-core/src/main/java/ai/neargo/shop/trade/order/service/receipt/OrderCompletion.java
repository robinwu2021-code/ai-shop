package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.entity.OrdShipment;

import java.util.List;

/**
 * 订单是否已全部交付（订单服务 2.0 · W5）。判据：<b>所有行都已发出</b>（归属某发货单）
 * <b>且所有活跃发货单都已确认收货</b>（RECEIVED）。部分发货/部分收货时返回 false。
 */
public final class OrderCompletion {

    private OrderCompletion() {
    }

    public static boolean isComplete(List<OrdItem> items, List<OrdShipment> shipments) {
        List<OrdShipment> active = shipments.stream()
                .filter(s -> !OrdShipment.VOIDED.equals(s.getStatus()))
                .toList();
        if (active.isEmpty() || items.isEmpty()) {
            return false;
        }
        boolean allItemsShipped = items.stream().allMatch(i -> i.getShipmentNo() != null);
        boolean allActiveReceived = active.stream()
                .allMatch(s -> OrdShipment.RECEIVED.equals(s.getStatus()));
        return allItemsShipped && allActiveReceived;
    }
}
