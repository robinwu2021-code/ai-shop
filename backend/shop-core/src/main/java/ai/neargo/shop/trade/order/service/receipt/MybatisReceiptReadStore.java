package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.aftersale.AfterSaleSlots;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** All fulfillment reads follow the child lock; explicit buyer checks replace ambient data-scope assumptions. */
@Component
public class MybatisReceiptReadStore implements ReceiptReadStore {
    private static final Set<String> DISPATCH_EVENTS = Set.of("SHIPPED", "SHIP_BY_MERCHANT", "VERIFIED",
            "ARRIVED_AT_PICKUP", "DELIVERED_BY_MERCHANT");
    private final StatusCasMapper locks;
    private final ShipmentMapper shipments;
    private final OrderItemMapper items;
    private final PaymentMapper payments;
    private final OrderStatusLogMapper logs;
    private final AfterSaleMapper afterSales;

    public MybatisReceiptReadStore(StatusCasMapper locks, ShipmentMapper shipments, OrderItemMapper items,
                                  PaymentMapper payments, OrderStatusLogMapper logs, AfterSaleMapper afterSales) {
        this.locks = locks;
        this.shipments = shipments;
        this.items = items;
        this.payments = payments;
        this.logs = logs;
        this.afterSales = afterSales;
    }

    @Override
    public OrdSubOrder findOrder(String no) {
        return DataScopeContext.executeWithoutScope(() -> locks.lockOrder(no));
    }

    @Override
    public OrdShipment findShipment(String no) {
        return DataScopeContext.executeWithoutScope(() -> shipments.selectOne(
                Wrappers.<OrdShipment>lambdaQuery().eq(OrdShipment::getShipmentNo, no).last("LIMIT 1")));
    }

    @Override
    public List<OrdShipment> shipmentsOf(String no) {
        return DataScopeContext.executeWithoutScope(() -> shipments.selectList(
                Wrappers.<OrdShipment>lambdaQuery().eq(OrdShipment::getSubOrderNo, no).orderByAsc(OrdShipment::getId)));
    }

    @Override
    public List<OrdItem> itemsOf(String no) {
        return DataScopeContext.executeWithoutScope(() -> items.selectList(
                Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getSubOrderNo, no).orderByAsc(OrdItem::getId)));
    }

    /** The parent and child must agree on the actual buyer, including calls with no ambient scope. */
    public boolean ownedBy(OrdSubOrder order, String buyer) {
        if (buyer == null || buyer.isBlank() || !buyer.equals(order.getUserNo())) {
            return false;
        }
        OrdOrder parent = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, order.getOrderNo()).last("LIMIT 1")));
        return parent != null && buyer.equals(parent.getUserNo());
    }

    /** Inconsistent historical child states cannot turn an unpaid or closed parent into delivered goods. */
    public boolean parentPaid(OrdSubOrder order) {
        OrdOrder parent = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, order.getOrderNo()).last("LIMIT 1")));
        return parent != null && OrdOrder.PAID.equals(parent.getStatus());
    }

    @Override
    public boolean dispatched(OrdSubOrder order, OrdShipment shipment) {
        List<OrdItem> bound = itemsOf(order.getSubOrderNo()).stream()
                .filter(i -> shipment.getShipmentNo().equals(i.getShipmentNo())).toList();
        return OrdSubOrder.FULFILLING.equals(order.getStatus()) && !bound.isEmpty()
                && bound.stream().allMatch(OrderCompletion::fullyShipped)
                && fulfillmentLogs(order.getSubOrderNo()).stream().anyMatch(MybatisReceiptReadStore::physical);
    }

    /** Old rows without ord_shipment still need persisted physical fulfillment, rather than payment alone. */
    public boolean legacyFulfilled(OrdSubOrder order) {
        if (!OrdSubOrder.FULFILLING.equals(order.getStatus())) {
            return false;
        }
        boolean express = OrdSubOrder.EXPRESS.equals(order.getFulfillment())
                && order.getExpressNo() != null && !order.getExpressNo().isBlank()
                && order.getExpressCompany() != null && !order.getExpressCompany().isBlank();
        return express || fulfillmentLogs(order.getSubOrderNo()).stream().anyMatch(MybatisReceiptReadStore::physical);
    }

    /** Latest dispatch fact preserves the existing 15-day fallback while excluding service payment events. */
    public Long shippedAt(String no) {
        return fulfillmentLogs(no).stream().filter(MybatisReceiptReadStore::physical)
                .map(OrdStatusLog::getAt).filter(at -> at != null).max(Long::compareTo).orElse(null);
    }

    /** WAIT_RECEIPT retains its receipt notification path; all other active disputes prevent auto receipt. */
    public boolean openAfterSale(OrdSubOrder order) {
        List<OrdAfterSale> tickets = DataScopeContext.executeWithoutScope(() -> afterSales.selectList(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSubOrderNo, order.getSubOrderNo())));
        boolean disputed = tickets.stream().anyMatch(as -> !AfterSaleSlots.RELEASING.contains(as.getStatus())
                && !OrdAfterSale.WAIT_RECEIPT.equals(as.getStatus()));
        String slot = DataScopeContext.executeWithoutScope(() -> locks.openAfterSaleNo(order.getSubOrderNo()));
        boolean receiptSlot = tickets.stream().anyMatch(as -> OrdAfterSale.WAIT_RECEIPT.equals(as.getStatus())
                && as.getAfterSaleNo().equals(slot));
        return disputed || slot != null && !receiptSlot;
    }

    private List<OrdStatusLog> fulfillmentLogs(String no) {
        return DataScopeContext.executeWithoutScope(() -> logs.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                .eq(OrdStatusLog::getSubOrderNo, no).eq(OrdStatusLog::getStatus, OrdSubOrder.FULFILLING)));
    }

    private static boolean physical(OrdStatusLog log) {
        return log.getEvent() == null ? OrdStatusLog.BY_MERCHANT.equals(log.getOperatorType())
                : DISPATCH_EVENTS.contains(log.getEvent());
    }
}
