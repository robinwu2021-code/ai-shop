package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;

import java.util.List;

/** 收货读出口（小 port），便于 ReceiptHandler 纯逻辑单测。 */
public interface ReceiptReadStore {

    OrdShipment findShipment(String shipmentNo);

    List<OrdShipment> shipmentsOf(String subOrderNo);

    List<OrdItem> itemsOf(String subOrderNo);

    OrdSubOrder findOrder(String subOrderNo);
}
