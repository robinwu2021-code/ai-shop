package ai.neargo.shop.trade.order.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.common.StoreMarkets;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.trade.ShippingUploadPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.command.ShipResult;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.trade.order.service.ship.MybatisShipmentReadStore;
import ai.neargo.shop.trade.order.service.ship.MybatisShipmentWriteStore;
import ai.neargo.shop.trade.order.service.ship.ShipHandler;
import ai.neargo.shop.trade.order.service.ship.WaybillNormalizer;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;

/** Express production facade locks before rechecking authorization, payment, child state and refunds. */
@Service
public class OrderShippingServiceImpl implements OrderShippingService {
    private final MybatisShipmentReadStore read;
    private final ShipHandler handler;
    private final OutboxEventBus events;
    private final ObjectProvider<ShippingUploadPort> uploads;
    private final StoreMarkets markets;

    public OrderShippingServiceImpl(MybatisShipmentReadStore read, MybatisShipmentWriteStore write,
                                    TradeStatusWriter statuses, OutboxEventBus events,
                                    ObjectProvider<ShippingUploadPort> uploads, StoreMarkets markets) {
        this.read = read;
        this.handler = new ShipHandler(read, write, new OrderTransitions(statuses), () -> BizKey.next(BizKey.SHIPMENT));
        this.events = events;
        this.uploads = uploads;
        this.markets = markets;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShipResult ship(String subOrderNo, ShipCommand command, OrderActor actor) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null || actor == null || !actor.owns(order)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        command = requireEligible(order, command);
        List<OrdShipment> previous = read.activeOf(subOrderNo);
        String shipmentNo = handler.ship(subOrderNo, command.carrier(), command.waybillNo(), command.itemIds(),
                Operator.merchant(actor.operatorNo()));
        if (previous.stream().noneMatch(s -> shipmentNo.equals(s.getShipmentNo()))) {
            // Zero is an immutable source generation; BaseEntity.version remains the mutable CAS revision.
            events.publish(new OrderEvents.SubOrderShipped(subOrderNo, order.getOrderNo(), order.getEntityNo(),
                    order.getUserNo(), order.getFulfillment(), command.carrier(),
                    WaybillNormalizer.normalize(command.waybillNo()), shipmentNo, 0L));
            ShippingUploadPort upload = uploads.getIfAvailable();
            if (upload != null) {
                upload.enqueue(order.getOrderNo(), subOrderNo, order.getFulfillment());
            }
        }
        return new ShipResult(subOrderNo, shipmentNo, OrdSubOrder.FULFILLING);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShipResult correctByPlatform(String subOrderNo, String expectedShipmentNo, ShipCommand command,
                                        String operatorNo, String reason) {
        OrdSubOrder order = read.findOrder(subOrderNo);
        if (order == null || operatorNo == null || operatorNo.isBlank() || reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        command = requireEligible(order, command);
        List<OrdShipment> live = read.activeOf(subOrderNo);
        if (!OrdSubOrder.FULFILLING.equals(order.getStatus()) || live.size() != 1
                || !live.getFirst().getShipmentNo().equals(expectedShipmentNo)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String no = handler.correct(subOrderNo, command.carrier(), command.waybillNo(),
                Operator.platform(operatorNo), reason);
        if (!expectedShipmentNo.equals(no)) {
            events.publish(new OrderEvents.SubOrderShipped(subOrderNo, order.getOrderNo(), order.getEntityNo(),
                    order.getUserNo(), order.getFulfillment(), command.carrier(),
                    WaybillNormalizer.normalize(command.waybillNo()), no, 0L));
        }
        return new ShipResult(subOrderNo, no, OrdSubOrder.FULFILLING);
    }

    /**
     * @return 承运商归一成码之后的命令。市场取<b>订单自己的门店</b>（TDD-商户App快递公司 §2）：
     *         叫快递取件回填运单时调用方的门店号是空的，按调用方取会取不到
     */
    private ShipCommand requireEligible(OrdSubOrder order, ShipCommand command) {
        if (command == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String carrier = ExpressCompanies.resolve(markets.of(order.getStoreNo()), command.carrier())
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
        if (!OrdSubOrder.EXPRESS.equals(order.getFulfillment()) || !read.parentPaid(order.getOrderNo())
                || (!OrdSubOrder.WAIT_FULFILL.equals(order.getStatus())
                && !OrdSubOrder.FULFILLING.equals(order.getStatus()))) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (read.openAfterSale(order)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new ShipCommand(command.itemIds(), carrier, command.waybillNo());
    }
}
