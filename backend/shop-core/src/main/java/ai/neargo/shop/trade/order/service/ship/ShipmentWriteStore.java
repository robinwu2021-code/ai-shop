package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.entity.OrdShipment;

import java.util.List;

/** 发货写出口（小 port）。接线阶段用 ShipmentMapper/TradeMappers 实现。 */
public interface ShipmentWriteStore {

    void saveShipment(OrdShipment shipment);

    /** Retire an old package through the shared transition gate before rebinding its rows. */
    void supersede(OrdShipment old, String replacement, Operator operator);

    /** Preserve the caller's real correction reason while old test adapters retain their original API. */
    default void supersede(OrdShipment old, String replacement, Operator operator, String reason) {
        supersede(old, replacement, operator);
    }

    /** Maintain the existing HTTP/JSON shipping fields alongside package facts. */
    void saveLegacyFields(OrdSubOrder order, String carrier, String waybillNo);

    /** 更新行的发货归属（shipment_no / shipped_qty）。 */
    void bindItems(List<OrdItem> items);
}
