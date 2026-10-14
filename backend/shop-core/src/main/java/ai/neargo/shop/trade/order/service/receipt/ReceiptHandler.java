package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;

/**
 * 确认收货（订单服务 2.0 · W5）：发货单置 RECEIVED；若该订单<b>所有行已发且所有活跃发货单都已收货</b>，
 * 订单置 COMPLETED（{@link OrderCompletion}）。部分收货时订单保持履约中。幂等：已收货重复点为空操作。
 */
public class ReceiptHandler {

    private final ReceiptReadStore read;
    private final ReceiptWriteStore write;
    private final OrderTransitions transitions;

    public ReceiptHandler(ReceiptReadStore read, ReceiptWriteStore write, OrderTransitions transitions) {
        this.read = read;
        this.write = write;
        this.transitions = transitions;
    }

    public void confirm(String subOrderNo, String shipmentNo, Operator op) {
        OrdShipment s = read.findShipment(shipmentNo);
        if (s == null || !subOrderNo.equals(s.getSubOrderNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdShipment.RECEIVED.equals(s.getStatus())) {
            return;   // 幂等
        }
        if (!canReceive(s.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);   // 包裹尚未发货，不能确认收货
        }
        s.setStatus(OrdShipment.RECEIVED);
        write.saveShipment(s);
        completeIfAllReceived(subOrderNo, op);
    }

    private void completeIfAllReceived(String subOrderNo, Operator op) {
        if (OrderCompletion.isComplete(read.itemsOf(subOrderNo), read.shipmentsOf(subOrderNo))) {
            OrdSubOrder order = read.findOrder(subOrderNo);
            transitions.applySub(order, OrdSubOrder.COMPLETED, op, "确认收货");
        }
    }

    private static boolean canReceive(String status) {
        return OrdShipment.SHIPPED.equals(status) || OrdShipment.DELIVERED.equals(status);
    }
}
