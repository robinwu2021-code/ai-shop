package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentContext;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import org.springframework.stereotype.Component;

/** Package receipt uses the shared CAS and event log in the caller's child-serialized transaction. */
@Component
public class MybatisReceiptWriteStore implements ReceiptWriteStore {
    private final TradeStatusWriter writer;

    public MybatisReceiptWriteStore(TradeStatusWriter writer) {
        this.writer = writer;
    }

    @Override
    public void receiveShipment(OrdShipment shipment, Operator operator, boolean dispatched) {
        Actor actor = OrdStatusLog.BY_USER.equals(operator.type()) ? Actor.BUYER : Actor.SYSTEM;
        writer.shipment(shipment, ShipmentEvent.RECEIPT_CONFIRMED, actor, operator.no(), "确认包裹收货",
                new ShipmentContext(true, true, true, dispatched));
    }
}
