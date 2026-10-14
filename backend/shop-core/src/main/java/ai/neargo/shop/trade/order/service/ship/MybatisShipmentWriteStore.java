package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentContext;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Locale;

/** Explicit row-ID writes fail closed on zero rows so the surrounding primary transaction rolls back. */
@Component
public class MybatisShipmentWriteStore implements ShipmentWriteStore {
    private final ShipmentMapper shipments;
    private final OrderItemMapper items;
    private final StoreOrderMapper orders;
    private final TradeStatusWriter statuses;

    public MybatisShipmentWriteStore(ShipmentMapper shipments, OrderItemMapper items, StoreOrderMapper orders,
                                     TradeStatusWriter statuses) {
        this.shipments = shipments;
        this.items = items;
        this.orders = orders;
        this.statuses = statuses;
    }

    @Override
    public void saveShipment(OrdShipment shipment) {
        try {
            requireOne(DataScopeContext.executeWithoutScope(() -> shipments.insert(shipment)));
        } catch (DuplicateKeyException e) {
            String detail = e.getMostSpecificCause().getMessage();
            if (detail != null && detail.toLowerCase(Locale.ROOT).contains("uk_ord_shipment_active_waybill")) {
                throw BizException.of(ErrorCode.WAYBILL_DUPLICATED);
            }
            throw e;
        }
    }

    @Override
    public void bindItems(List<OrdItem> rows) {
        for (OrdItem item : rows) {
            requireOne(DataScopeContext.executeWithoutScope(() -> items.update(null,
                    Wrappers.<OrdItem>lambdaUpdate().eq(OrdItem::getId, item.getId())
                            .eq(OrdItem::getSubOrderNo, item.getSubOrderNo()).eq(OrdItem::getVersion, item.getVersion())
                            .set(OrdItem::getShipmentNo, item.getShipmentNo())
                            .set(OrdItem::getShippedQty, item.getShippedQty())
                            .set(OrdItem::getVersion, item.getVersion() + 1))));
        }
    }

    @Override
    public void supersede(OrdShipment old, String replacement, Operator operator) {
        statuses.shipment(old, ShipmentEvent.CORRECT_WAYBILL, Actor.MERCHANT, operator.no(),
                "商家改快递单号：" + old.getWaybillNo(), ShipmentContext.ok());
        requireOne(DataScopeContext.executeWithoutScope(() -> shipments.update(null,
                Wrappers.<OrdShipment>lambdaUpdate().eq(OrdShipment::getId, old.getId())
                        .eq(OrdShipment::getVersion, old.getVersion())
                        .set(OrdShipment::getVoidedAt, System.currentTimeMillis())
                        .set(OrdShipment::getVoidReason, "商家更正运单")
                        .set(OrdShipment::getSupersededBy, replacement))));
    }

    @Override
    public void saveLegacyFields(OrdSubOrder order, String carrier, String waybillNo) {
        requireOne(DataScopeContext.executeWithoutScope(() -> orders.update(null,
                Wrappers.<OrdSubOrder>lambdaUpdate().eq(OrdSubOrder::getId, order.getId())
                        .eq(OrdSubOrder::getVersion, order.getVersion())
                        .set(OrdSubOrder::getExpressCompany, carrier).set(OrdSubOrder::getExpressNo, waybillNo)
                        .set(OrdSubOrder::getVersion, order.getVersion() + 1))));
        order.setExpressCompany(carrier);
        order.setExpressNo(waybillNo);
        order.setVersion(order.getVersion() + 1);
    }

    private static void requireOne(int rows) {
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
