package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.GoodsDesc;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Sorted immutable generation0 references preserve every store, including explicit unmapped legacy facts. */
@Component
public class MybatisShippingUploadSources implements ShippingUploadSources {
    private static final Set<String> CLOSED = Set.of(OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED);
    private final StoreOrderMapper orders;
    private final ShipmentMapper shipments;
    private final OrderItemMapper items;

    public MybatisShippingUploadSources(StoreOrderMapper orders, ShipmentMapper shipments, OrderItemMapper items) {
        this.orders = orders;
        this.shipments = shipments;
        this.items = items;
    }

    @Override
    public ShippingUploadSnapshot snapshotOf(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> capture(orderNo));
    }

    private ShippingUploadSnapshot capture(String orderNo) {
        List<OrdSubOrder> required = orders.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getOrderNo, orderNo).orderByAsc(OrdSubOrder::getSubOrderNo)).stream()
                .filter(s -> !CLOSED.contains(s.getStatus())).toList();
        if (required.isEmpty() || required.stream().noneMatch(s -> OrdSubOrder.EXPRESS.equals(s.getFulfillment()))) {
            return null;
        }
        List<OrdShipment> packages = shipments.selectList(Wrappers.<OrdShipment>lambdaQuery()
                .in(OrdShipment::getSubOrderNo, required.stream().map(OrdSubOrder::getSubOrderNo).toList())
                .ne(OrdShipment::getStatus, OrdShipment.VOIDED).orderByAsc(OrdShipment::getShipmentNo));
        List<OrdItem> rows = items.selectList(Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getOrderNo, orderNo));
        List<WxShippingPort.ShippingItem> sent = new ArrayList<>();
        for (OrdSubOrder sub : required) {
            if (!OrdSubOrder.EXPRESS.equals(sub.getFulfillment()) || !dispatched(sub)) { continue; }
            List<OrdShipment> current = packages.stream()
                    .filter(p -> sub.getSubOrderNo().equals(p.getSubOrderNo())).toList();
            if (current.isEmpty() && sub.getExpressNo() != null && !sub.getExpressNo().isBlank()) {
                sent.add(item(sub, null, sub.getExpressNo(), sub.getExpressCompany(), rows));
            } else {
                current.forEach(p -> sent.add(item(sub, p.getShipmentNo(), p.getWaybillNo(), p.getCarrier(), rows)));
            }
        }
        sent.sort(Comparator.comparing(WxShippingPort.ShippingItem::subOrderNo)
                .thenComparing(s -> s.sourceShipmentNo() == null ? "" : s.sourceShipmentNo()));
        boolean all = required.stream().allMatch(s -> dispatched(s)
                && sent.stream().anyMatch(p -> s.getSubOrderNo().equals(p.subOrderNo())));
        String blocker = required.stream().anyMatch(s -> !OrdSubOrder.EXPRESS.equals(s.getFulfillment()))
                ? "混合履约支付单需人工核对微信发货，不能用一张快递面单冒充全部履约" : null;
        return new ShippingUploadSnapshot(sent, required.size() > 1 || sent.size() > 1 ? 2 : 1, all, blocker);
    }

    private static boolean dispatched(OrdSubOrder s) {
        return OrdSubOrder.FULFILLING.equals(s.getStatus()) || OrdSubOrder.COMPLETED.equals(s.getStatus());
    }

    private static WxShippingPort.ShippingItem item(OrdSubOrder sub, String sourceNo, String number,
                                                    String carrier, List<OrdItem> rows) {
        List<String> names = rows.stream().filter(i -> sub.getSubOrderNo().equals(i.getSubOrderNo()))
                .filter(i -> sourceNo == null || Objects.equals(sourceNo, i.getShipmentNo()))
                .map(OrdItem::getTitle).toList();
        return new WxShippingPort.ShippingItem(sub.getSubOrderNo(), sourceNo, sourceNo == null ? null : 0L,
                number, carrier, GoodsDesc.of(names, GoodsDesc.SHIPPING_MAX));
    }
}
