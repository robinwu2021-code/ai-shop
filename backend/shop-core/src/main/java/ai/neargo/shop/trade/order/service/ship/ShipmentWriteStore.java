package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.entity.OrdShipment;

import java.util.List;

/** 发货写出口（小 port）。接线阶段用 ShipmentMapper/TradeMappers 实现。 */
public interface ShipmentWriteStore {

    void saveShipment(OrdShipment shipment);

    /** 更新行的发货归属（shipment_no / shipped_qty）。 */
    void bindItems(List<OrdItem> items);
}
