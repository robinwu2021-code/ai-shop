package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;

/**
 * 确认收货（订单服务 2.0 · W5）：发货单置 RECEIVED。
 * 所有行已发且所有活跃发货单都已收货时，订单置 COMPLETED（{@link OrderCompletion}）。
 * 部分收货时订单保持履约中；已收货重复点为空操作。
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

    /** Return a newly completed child so notifications run once rather than after every historical package. */
    public boolean confirm(String subOrderNo, String shipmentNo, Operator op) {
        return confirm(subOrderNo, shipmentNo, op, "确认收货");
    }

    /** The facade holds the child lock throughout package receipt, completion and local notifications. */
    public boolean confirm(String subOrderNo, String shipmentNo, Operator op, String reason) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        OrdShipment s = read.findShipment(shipmentNo);
        if (order == null || s == null || !subOrderNo.equals(s.getSubOrderNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdShipment.RECEIVED.equals(s.getStatus())) {
            return false;
        }
        boolean dispatched = read.dispatched(order, s);
        if (!OrdSubOrder.FULFILLING.equals(order.getStatus()) || !canReceive(s.getStatus(), dispatched)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);   // 包裹尚未发货，不能确认收货
        }
        write.receiveShipment(s, op, dispatched);
        return completeIfAllReceived(subOrderNo, op, reason);
    }

    private boolean completeIfAllReceived(String subOrderNo, Operator op, String reason) {
        if (OrderCompletion.isComplete(read.itemsOf(subOrderNo), read.shipmentsOf(subOrderNo))) {
            OrdSubOrder order = read.findOrder(subOrderNo);
            if (!OrdSubOrder.COMPLETED.equals(order.getStatus())) {
                transitions.applySub(order, OrdSubOrder.COMPLETED, op, reason);
                return true;
            }
        }
        return false;
    }

    private static boolean canReceive(String status, boolean dispatched) {
        return OrdShipment.SHIPPED.equals(status) || OrdShipment.DELIVERED.equals(status)
                || dispatched && OrdShipment.CREATED.equals(status);
    }
}
