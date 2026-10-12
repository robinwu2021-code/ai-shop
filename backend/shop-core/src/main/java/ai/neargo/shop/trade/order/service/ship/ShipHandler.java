package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderTransitions;

import java.util.List;
import java.util.function.Supplier;

/**
 * 发货主干（订单服务 2.0 · W4）：清洗 → 格式 → 重号 → 建发货单 → 行归属 → 订单置履约中。
 *
 * <p>支持一个订单多张发货单（部分发货/多包裹）；重号（同承运商单号被别的订单的活跃发货单占用）拒绝；
 * 同单重复填同号为幂等（返回原发货单）。对比现状 {@code ship} 只改子单一个 express_no。
 */
public class ShipHandler {

    private final ShipmentReadStore read;
    private final ShipmentWriteStore write;
    private final OrderTransitions transitions;
    private final Supplier<String> shipmentNoGen;

    public ShipHandler(ShipmentReadStore read, ShipmentWriteStore write,
                       OrderTransitions transitions, Supplier<String> shipmentNoGen) {
        this.read = read;
        this.write = write;
        this.transitions = transitions;
        this.shipmentNoGen = shipmentNoGen;
    }

    /** @return 发货单号（同单重复填同号时为原发货单号） */
    public String ship(String subOrderNo, String carrier, String rawWaybillNo, List<String> itemSkuNos, Operator op) {
        OrdSubOrder order = requireOrder(subOrderNo);
        String waybillNo = normalizeAndValidate(rawWaybillNo);
        OrdShipment dup = read.findActiveWaybill(carrier, waybillNo);
        if (dup != null) {
            if (subOrderNo.equals(dup.getSubOrderNo())) {
                return dup.getShipmentNo();   // 幂等：同单重复填同号
            }
            throw BizException.of(ErrorCode.BAD_REQUEST, "该运单号已用于订单 " + dup.getSubOrderNo());
        }
        return create(order, carrier, waybillNo, itemSkuNos, op);
    }

    private OrdSubOrder requireOrder(String subOrderNo) {
        OrdSubOrder o = read.findOrder(subOrderNo);
        if (o == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return o;
    }

    private String normalizeAndValidate(String raw) {
        String no = WaybillNormalizer.normalize(raw);
        if (!WaybillFormat.isValid(no)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "运单号格式不正确");
        }
        return no;
    }

    private String create(OrdSubOrder order, String carrier, String waybillNo, List<String> itemSkuNos, Operator op) {
        List<OrdItem> toShip = selectUnshipped(read.itemsOf(order.getSubOrderNo()), itemSkuNos);
        if (toShip.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "没有可发货的商品行");
        }
        String shipmentNo = shipmentNoGen.get();
        OrdShipment shipment = ShipmentDraftFactory.express(
                shipmentNo, order.getSubOrderNo(), order.getStoreNo(), carrier, waybillNo);
        toShip.forEach(i -> ShipmentDraftFactory.bind(i, shipmentNo));
        write.saveShipment(shipment);
        write.bindItems(toShip);
        transitions.applyMerchantSub(order, OrdSubOrder.FULFILLING, op, "商家发货：" + waybillNo);
        return shipmentNo;
    }

    private static List<OrdItem> selectUnshipped(List<OrdItem> items, List<String> skuNos) {
        boolean all = skuNos == null || skuNos.isEmpty();
        return items.stream()
                .filter(i -> i.getShipmentNo() == null)
                .filter(i -> all || skuNos.contains(i.getSkuNo()))
                .toList();
    }
}
