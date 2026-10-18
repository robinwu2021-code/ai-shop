package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.fulfillment.FreightPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdExpressPickup;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.ExpressPickupMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.MerchantOrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Paid but not handed-off express/self-delivery changes, with price and promise checks. */
@Service
public class FulfillmentChangeServiceImpl implements ai.neargo.shop.trade.service.FulfillmentChangeService {

    private final StatusCasMapper locks;
    private final StoreOrderMapper storeOrders;
    private final OrderItemMapper items;
    private final AfterSaleMapper afterSales;
    private final ShipmentMapper shipments;
    private final ExpressPickupMapper pickups;
    private final OrderStatusLogMapper logs;
    private final MerchantOrderService orders;
    private final MerchantQueryPort merchants;
    private final UserQueryPort users;
    private final GoodsQueryPort goods;
    private final FreightPort freight;
    private final OutboxEventBus events;

    public FulfillmentChangeServiceImpl(StatusCasMapper locks, StoreOrderMapper storeOrders,
                                    OrderItemMapper items, AfterSaleMapper afterSales,
                                    ShipmentMapper shipments, ExpressPickupMapper pickups,
                                    OrderStatusLogMapper logs, MerchantOrderService orders,
                                    MerchantQueryPort merchants, UserQueryPort users, GoodsQueryPort goods,
                                    FreightPort freight, OutboxEventBus events) {
        this.locks = locks;
        this.storeOrders = storeOrders;
        this.items = items;
        this.afterSales = afterSales;
        this.shipments = shipments;
        this.pickups = pickups;
        this.logs = logs;
        this.orders = orders;
        this.merchants = merchants;
        this.users = users;
        this.goods = goods;
        this.freight = freight;
        this.events = events;
    }

    public Options options(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = scoped(merchantNo, storeNo, subOrderNo, false);
        return optionsOf(sub);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Changed change(String merchantNo, String storeNo, String subOrderNo,
                          String target, String reason, Long expectedVersion, String operatorNo) {
        if (reason == null || reason.isBlank() || reason.length() > 200
                || expectedVersion == null || operatorNo == null
                || operatorNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdSubOrder sub = scoped(merchantNo, storeNo, subOrderNo, true);
        if (!Objects.equals(sub.getVersion(), expectedVersion)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Option option = optionsOf(sub).options().stream()
                .filter(o -> o.fulfillment().equals(target)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
        if (!option.allowed() || target.equals(sub.getFulfillment())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String previous = sub.getFulfillment();
        int changed = DataScopeContext.executeWithoutScope(() ->
                locks.changeFulfillment(subOrderNo, target, expectedVersion));
        if (changed != 1) throw BizException.of(ErrorCode.CONFLICT);
        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setAggregateType("ORDER");
        log.setAggregateNo(subOrderNo);
        log.setEvent("FULFILLMENT_CHANGED");
        log.setFromStatus(previous);
        log.setStatus(target);
        log.setLabel("配送方式由 " + previous + " 改为 " + target + "：" + reason.trim());
        log.setOperatorType(OrdStatusLog.BY_MERCHANT);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> logs.insert(log));
        events.publish(new OrderEvents.SubOrderFulfillmentChanged(subOrderNo, sub.getOrderNo(),
                sub.getUserNo(), sub.getEntityNo(), sub.getStoreNo(), previous, target, reason.trim()));
        return new Changed(orders.detail(merchantNo, storeNo, subOrderNo), expectedVersion + 1);
    }

    private OrdSubOrder scoped(String merchantNo, String storeNo, String subOrderNo, boolean lock) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() -> lock
                ? locks.lockOrder(subOrderNo) : ordersRow(subOrderNo));
        if (sub == null || !merchantNo.equals(sub.getEntityNo()) || storeNo == null
                || !storeNo.equals(sub.getStoreNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return sub;
    }

    private OrdSubOrder ordersRow(String subOrderNo) {
        return storeOrders.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1"));
    }

    private Options optionsOf(OrdSubOrder sub) {
        String current = sub.getFulfillment();
        List<String> pair = List.of(Fulfillments.MERCHANT_DELIVERY, Fulfillments.EXPRESS);
        List<Option> options = pair.stream().map(target -> {
            if (target.equals(current)) return new Option(target, true, null);
            String reason = issue(sub, target);
            return new Option(target, reason == null, reason);
        }).toList();
        return new Options(sub.getSubOrderNo(), current, sub.getVersion() == null ? 0 : sub.getVersion(), options);
    }

    private String issue(OrdSubOrder sub, String target) {
        if (!Fulfillments.EXPRESS.equals(sub.getFulfillment())
                && !Fulfillments.MERCHANT_DELIVERY.equals(sub.getFulfillment())) return "此订单不支持切换配送方式";
        if (!OrdSubOrder.WAIT_FULFILL.equals(sub.getStatus())
                || !OrdOrder.PAID.equals(DataScopeContext.executeWithoutScope(() ->
                locks.paymentStatus(sub.getOrderNo())))) return "订单已交运或尚未支付";
        if (sub.getExpressNo() != null && !sub.getExpressNo().isBlank()) return "请先取消旧运单并核实货物仍在店";
        if (sub.getReceiverAddress() == null || sub.getReceiverAddress().isBlank()
                || sub.getReceiverPhone() == null || sub.getReceiverPhone().isBlank()) return "收件地址或电话不完整";
        if (DataScopeContext.executeWithoutScope(() -> locks.openAfterSaleNo(sub.getSubOrderNo())) != null
                || openAfterSale(sub.getSubOrderNo()))
            return "订单有进行中的售后";
        if (DataScopeContext.executeWithoutScope(() -> shipments.selectCount(
                Wrappers.<OrdShipment>lambdaQuery().eq(OrdShipment::getSubOrderNo, sub.getSubOrderNo())
                        .ne(OrdShipment::getStatus, OrdShipment.VOIDED))) > 0) return "订单已有有效发货单";
        if (DataScopeContext.executeWithoutScope(() -> pickups.selectCount(
                Wrappers.<OrdExpressPickup>lambdaQuery().eq(OrdExpressPickup::getSubOrderNo, sub.getSubOrderNo())
                        .notIn(OrdExpressPickup::getStatus, OrdExpressPickup.CANCELLED,
                                OrdExpressPickup.FAILED))) > 0) return "请先取消快递取件预约";
        var enabled = merchants.enabledFulfillments(sub.getEntityNo(), sub.getStoreNo());
        if (Fulfillments.MERCHANT_DELIVERY.equals(target)
                && (sub.getCommunityNo() == null || sub.getCommunityNo().isBlank())) {
            return "收货区域信息不完整，无法核实自送范围";
        }
        if (Fulfillments.MERCHANT_DELIVERY.equals(target)) {
            String radiusIssue = deliveryRadiusIssue(sub);
            if (radiusIssue != null) return radiusIssue;
        }
        if (enabled.isEmpty() || !merchants.enabledFulfillmentsFor(sub.getEntityNo(), sub.getStoreNo(),
                sub.getCommunityNo()).contains(target)) return "门店未开通此配送方式或不覆盖收货区域";
        List<OrdItem> lines = DataScopeContext.executeWithoutScope(() -> items.selectList(
                Wrappers.<OrdItem>lambdaQuery().eq(OrdItem::getSubOrderNo, sub.getSubOrderNo())));
        if (lines.isEmpty()) return "订单商品信息不完整";
        Map<String, GoodsQueryPort.SkuSnapshot> snapshots = goods.snapshot(
                lines.stream().map(OrdItem::getSkuNo).toList(), Map.of(sub.getEntityNo(), sub.getStoreNo()));
        if (lines.stream().anyMatch(i -> snapshots.get(i.getSkuNo()) == null
                || snapshots.get(i.getSkuNo()).fulfillments() == null
                || !snapshots.get(i.getSkuNo()).fulfillments().contains(target))) return "商品不支持此配送方式";
        Long fee = targetFee(sub, target, lines, snapshots);
        if (fee == null) return "无法核实目标配送方式的运费";
        if (!fee.equals(sub.getFreightAmount())) return "切换将改变运费，请联系买家重新下单";
        return null;
    }

    private String deliveryRadiusIssue(OrdSubOrder sub) {
        var origin = merchants.deliveryOrigin(sub.getEntityNo(), sub.getStoreNo()).orElse(null);
        if (origin == null || origin.radiusM() <= 0) return null;
        if (sub.getAddressId() == null || sub.getAddressId().isBlank()) return "无法核实订单地址的自送距离";
        var receiver = users.receiverOf(sub.getUserNo(), sub.getAddressId()).orElse(null);
        if (receiver == null || !Objects.equals(receiver.address(), sub.getReceiverAddress())) {
            return "订单收货地址已变更或不可核实";
        }
        if (receiver.latE6() == null || receiver.lngE6() == null) return "无法核实订单地址的自送距离";
        double metersPerDegree = 111_320d;
        double dLat = (origin.latE6() - receiver.latE6()) / 1e6 * metersPerDegree;
        double midLat = Math.toRadians((origin.latE6() + receiver.latE6()) / 2e6);
        double dLng = (origin.lngE6() - receiver.lngE6()) / 1e6 * metersPerDegree * Math.cos(midLat);
        return Math.hypot(dLat, dLng) > origin.radiusM() ? "收货地址超出门店自送范围" : null;
    }

    private boolean openAfterSale(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> afterSales.selectCount(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .notIn(OrdAfterSale::getStatus, OrdAfterSale.REFUNDED,
                                OrdAfterSale.REJECTED, OrdAfterSale.CLOSED))) > 0;
    }

    private Long targetFee(OrdSubOrder sub, String target, List<OrdItem> lines,
                           Map<String, GoodsQueryPort.SkuSnapshot> snapshots) {
        if (Fulfillments.MERCHANT_DELIVERY.equals(target)) {
            return merchants.merchantDeliveryFeeMinor(sub.getEntityNo(), sub.getStoreNo(),
                    sub.getGoodsAmount() == null ? 0L : sub.getGoodsAmount()).stream().boxed()
                    .findFirst().orElse(null);
        }
        Map<String, String> own = goods.freightTemplatesOf(lines.stream().map(OrdItem::getGoodsNo).toList());
        String storeTemplate = merchants.expressTemplateNo(sub.getEntityNo(), sub.getStoreNo()).orElse(null);
        List<FreightPort.FreightLine> quoteLines = lines.stream().map(i -> {
            String named = own.get(i.getGoodsNo());
            String template = named != null && freight.active(named) ? named : storeTemplate;
            Integer gram = snapshots.get(i.getSkuNo()).nominalGram();
            int qty = i.getQty() == null ? 0 : i.getQty();
            boolean weighed = gram != null && gram > 0;
            return new FreightPort.FreightLine(template, weighed ? gram * qty : 0,
                    weighed ? 0 : qty, i.getAmount() == null ? 0L : i.getAmount());
        }).toList();
        return freight.quoteMerged(quoteLines, sub.getReceiverAddress())
                .filter(q -> !q.rejected()).map(FreightPort.Quote::feeMinor).orElse(null);
    }
}
