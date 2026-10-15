package ai.neargo.shop.trade.order.service.receipt.impl;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.service.receipt.MybatisReceiptReadStore;
import ai.neargo.shop.trade.order.service.receipt.MybatisReceiptWriteStore;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import ai.neargo.shop.trade.order.service.receipt.ReceiptHandler;
import ai.neargo.shop.trade.order.service.receipt.ReceiptDeadlines;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Each child's current package receipts, completion and local reminder commit independently of the scanner. */
@Service
public class OrderReceiptCompleterImpl implements OrderReceiptCompleter {
    private static final long DAY = 86_400_000L;
    private final MybatisReceiptReadStore read;
    private final ReceiptHandler handler;
    private final LogisticsPort traces;
    private final TradeStatusWriter writer;
    private final AfterSaleService afterSales;

    public OrderReceiptCompleterImpl(MybatisReceiptReadStore read, MybatisReceiptWriteStore write,
                                     LogisticsPort traces, TradeStatusWriter writer,
                                     AfterSaleService afterSales) {
        this.read = read;
        this.handler = new ReceiptHandler(read, write, new OrderTransitions(writer));
        this.traces = traces;
        this.writer = writer;
        this.afterSales = afterSales;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean completeAuto(String subOrderNo, long now, int shippedDays, int signedDays) {
        if (shippedDays <= 0) {
            return false;
        }
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null || !OrdSubOrder.FULFILLING.equals(order.getStatus())
                || !read.parentPaid(order) || Fulfillments.isPickup(order.getFulfillment())
                || read.openAfterSale(order)) {
            return false;
        }
        List<OrdShipment> shipments = read.shipmentsOf(subOrderNo);
        Long shipped = read.shippedAt(subOrderNo);
        boolean completed = shipments.isEmpty()
                ? completeLegacy(order, now, shippedDays, signedDays, shipped)
                : receiveDue(subOrderNo, shipments, now, shippedDays, signedDays);
        if (completed) {
            afterSales.settleOnReceipt(subOrderNo);
        }
        return completed;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public boolean completeSigned(String subOrderNo, String shipmentNo, long signedAt) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null || !OrdSubOrder.FULFILLING.equals(order.getStatus())
                || !OrdSubOrder.EXPRESS.equals(order.getFulfillment()) || !read.parentPaid(order)
                || read.openAfterSale(order)) {
            return false;
        }
        List<OrdShipment> shipments = read.shipmentsOf(subOrderNo);
        OrdShipment current = shipments.stream().filter(s -> shipmentNo.equals(s.getShipmentNo())
                && OrdShipment.DELIVERED.equals(s.getStatus())).findFirst().orElse(null);
        if (current == null || current.getSignedAt() == null || current.getSignedAt() != signedAt) {
            return false;
        }
        Long dispatchedAt = ReceiptDeadlines.shippedAt(current, shipments);
        if (dispatchedAt == null || signedAt < dispatchedAt - 1_000L) {
            return false;
        }
        boolean completed = handler.confirm(subOrderNo, shipmentNo, Operator.system(), "物流签收后自动确认收货");
        if (completed) {
            afterSales.settleOnReceipt(subOrderNo);
        }
        return completed;
    }

    private boolean completeLegacy(OrdSubOrder order, long now, int shippedDays, int signedDays, Long shipped) {
        if (!read.legacyFulfilled(order)) {
            return false;
        }
        Long signed = signedDays > 0
                ? traces.signedAtOf(List.of(order.getSubOrderNo())).get(order.getSubOrderNo()) : null;
        if (signed != null) {
            Long firstTrace = traces.firstTraceAtOf(List.of(order.getSubOrderNo())).get(order.getSubOrderNo());
            if (firstTrace == null || shipped == null || firstTrace < shipped - 1_000L) {
                signed = null;
            }
        }
        String reason = dueReason(now, shippedDays, signedDays, shipped, signed);
        return reason != null && writer.order(order, OrderEvent.RECEIPT_AUTO_CONFIRMED, Actor.SYSTEM, null, reason,
                OrderContext.authorizedOnly().withAutoReceiptDue(true)).changed();
    }

    private boolean receiveDue(String no, List<OrdShipment> shipments, long now, int shippedDays,
                               int signedDays) {
        boolean completed = false;
        Long firstTrace = signedDays > 0 ? traces.firstTraceAtOf(List.of(no)).get(no) : null;
        for (OrdShipment shipment : shipments) {
            if (OrdShipment.VOIDED.equals(shipment.getStatus()) || OrdShipment.RECEIVED.equals(shipment.getStatus())) {
                continue;
            }
            Long dispatchedAt = ReceiptDeadlines.shippedAt(shipment, shipments);
            int trustedSignedDays = firstTrace != null && dispatchedAt != null
                    && firstTrace >= dispatchedAt - 1_000L ? signedDays : 0;
            String reason = dueReason(now, shippedDays, trustedSignedDays, dispatchedAt, shipment.getSignedAt());
            if (reason != null) {
                completed |= handler.confirm(no, shipment.getShipmentNo(), Operator.system(), reason);
            }
        }
        return completed;
    }

    private static String dueReason(long now, int shippedDays, int signedDays, Long shipped, Long signed) {
        if (signedDays > 0 && signed != null && shipped != null && signed >= shipped
                && signed <= now - (long) signedDays * DAY) {
            return "签收满 " + signedDays + " 天自动确认收货";
        }
        return shipped != null && shipped <= now - (long) shippedDays * DAY
                ? "发货满 " + shippedDays + " 天自动确认收货" : null;
    }
}
