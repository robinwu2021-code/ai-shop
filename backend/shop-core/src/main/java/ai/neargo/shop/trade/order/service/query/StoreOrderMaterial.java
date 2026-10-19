package ai.neargo.shop.trade.order.service.query;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import java.util.List;

/** Internal read aggregate, never an HTTP DTO; the legacy adapter retains its full response contract. */
public record StoreOrderMaterial(OrdSubOrder order, OrdOrder payment, List<OrdItem> items,
                                 List<OrdStatusLog> timeline, String storeName, List<OrdShipment> shipments) {
}
