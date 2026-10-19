package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;

import java.util.List;

/** 发货读出口（小 port），便于 ShipHandler 纯逻辑单测。接线阶段用 TradeMappers/ShipmentMapper 实现。 */
public interface ShipmentReadStore {

    /** 订单（子单）；不存在返回 null。 */
    OrdSubOrder findOrder(String subOrderNo);

    /** 该订单的全部行。 */
    List<OrdItem> itemsOf(String subOrderNo);

    /** Effective packages are used to validate replay contents and replacement identity. */
    List<OrdShipment> activeOf(String subOrderNo);

    /** 占用该 (carrier, waybillNo) 的活跃发货单（未作废/未取消）；没有返回 null —— 重号判定用。 */
    OrdShipment findActiveWaybill(String carrier, String waybillNo);
}
