package ai.neargo.shop.trade.order.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.service.OrderReceiptService;
import ai.neargo.shop.trade.order.service.receipt.MybatisReceiptReadStore;
import ai.neargo.shop.trade.order.service.receipt.MybatisReceiptWriteStore;
import ai.neargo.shop.trade.order.service.receipt.ReceiptHandler;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.AfterSaleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Lock before current reads so manual, auto and logistics commands share the same child serialization point. */
@Service
public class OrderReceiptServiceImpl implements OrderReceiptService {
    private final MybatisReceiptReadStore read;
    private final ReceiptHandler handler;
    private final TradeStatusWriter writer;
    private final AfterSaleService afterSales;

    public OrderReceiptServiceImpl(MybatisReceiptReadStore read, MybatisReceiptWriteStore write,
                                   TradeStatusWriter writer, AfterSaleService afterSales) {
        this.read = read;
        this.handler = new ReceiptHandler(read, write, new OrderTransitions(writer));
        this.writer = writer;
        this.afterSales = afterSales;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmReceipt(String subOrderNo, String shipmentNo, OrderActor actor) {
        ownedOrder(subOrderNo, actor);
        if (handler.confirm(subOrderNo, shipmentNo, Operator.user(actor.buyerNo()))) {
            afterSales.settleOnReceipt(subOrderNo);
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmCurrent(String subOrderNo, OrderActor actor) {
        OrdSubOrder order = ownedOrder(subOrderNo, actor);
        if (OrdSubOrder.COMPLETED.equals(order.getStatus())) {
            return;
        }
        List<OrdShipment> all = read.shipmentsOf(subOrderNo);
        List<OrdShipment> active = all.stream().filter(s -> !OrdShipment.VOIDED.equals(s.getStatus())).toList();
        boolean completed = false;
        if (all.isEmpty()) {
            if (!read.legacyFulfilled(order)) {
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            }
            completed = writer.order(order, OrderEvent.RECEIPT_CONFIRMED, Actor.BUYER, actor.buyerNo(),
                    "已确认收货（历史履约事实）", OrderContext.authorizedOnly()).changed();
        } else if (active.isEmpty()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        } else {
            for (OrdShipment shipment : active) {
                completed |= handler.confirm(subOrderNo, shipment.getShipmentNo(), Operator.user(actor.buyerNo()));
            }
        }
        if (completed) {
            afterSales.settleOnReceipt(subOrderNo);
        }
    }

    private OrdSubOrder ownedOrder(String subOrderNo, OrderActor actor) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null || actor == null || !read.ownedBy(order, actor.buyerNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!read.parentPaid(order)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        return order;
    }
}
