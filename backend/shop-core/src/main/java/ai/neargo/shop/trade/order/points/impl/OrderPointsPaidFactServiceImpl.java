package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort.VerifiedPayment;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.points.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Service
public class OrderPointsPaidFactServiceImpl implements OrderPointsPaidFactService {
    private final PaymentMapper orders;
    private final StoreOrderMapper subs;
    private final OrderItemMapper items;
    private final PointsOrderPlacementCoordinator placements;
    private final OrderPointsBindingService bindings;
    private final OrderPointsFactService facts;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsPaidFactServiceImpl(
            PaymentMapper orders,
            StoreOrderMapper subs,
            OrderItemMapper items,
            PointsOrderPlacementCoordinator placements,
            OrderPointsBindingService bindings,
            OrderPointsFactService facts) {
        this.orders = orders;
        this.subs = subs;
        this.items = items;
        this.placements = placements;
        this.bindings = bindings;
        this.facts = facts;
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public void recordPaid(String orderNo, VerifiedPayment proof) {
        var root =
                DataScopeContext.executeWithoutScope(
                        () ->
                                orders.selectOne(
                                        Wrappers.<OrdOrder>lambdaQuery()
                                                .eq(OrdOrder::getOrderNo, orderNo)
                                                .last("FOR UPDATE")));
        require(
                root != null
                        && OrdOrder.PAID.equals(root.getStatus())
                        && Boolean.TRUE.equals(root.getPointsIntegrated())
                        && root.getPayAmount() != null
                        && root.getPayAmount() == proof.amountMinor()
                        && root.getUserNo().equals(proof.userNo())
                        && root.getCurrency().equals(proof.currency()),
                "POINTS_PAID_SOURCE_MISMATCH");
        var placement = placements.inspect(orderNo);
        require(
                placement != null
                        && placement.phase() == PointsOrderPlacementCoordinator.Phase.READY
                        && placement.resolved() != null,
                "POINTS_PAID_PLACEMENT_MISSING");
        require(
                placement.draft().paymentStores().equals(proof.stores()),
                "POINTS_PAYMENT_ROUTE_MISMATCH");
        long version = root.getVersion() == null ? 0 : root.getVersion();
        Instant at =
                root.getPaidAt() == null
                        ? proof.succeededAt()
                        : Instant.ofEpochMilli(root.getPaidAt());
        for (var a : placement.resolved().admissions()) {
            var binding = bindings.find(a.subOrderNo());
            require(
                    binding != null
                            && binding.phase() == OrderPointsBindingService.Phase.BOUND
                            && binding.admission().equals(a),
                    "POINTS_PAID_BINDING_MISSING");
            var child =
                    DataScopeContext.executeWithoutScope(
                            () ->
                                    subs.selectOne(
                                            Wrappers.<OrdSubOrder>lambdaQuery()
                                                    .eq(OrdSubOrder::getSubOrderNo, a.subOrderNo())
                                                    .last("FOR UPDATE")));
            require(
                    child != null
                            && orderNo.equals(child.getOrderNo())
                            && a.userNo().equals(child.getUserNo())
                            && a.merchantNo().equals(child.getEntityNo())
                            && a.storeNo().equals(child.getStoreNo()),
                    "POINTS_PAID_CHILD_MISMATCH");
            var accepted = a.snapshot().payment();
            var paid = proof.forSubOrder(a.subOrderNo());
            require(
                    accepted.fundsPath().equals(paid.fundsPath())
                            && accepted.channel().equals(paid.channel())
                            && accepted.merchantNo().equals(paid.merchantNo())
                            && accepted.storeNo().equals(paid.storeNo())
                            && accepted.accountVersion().equals(paid.accountVersion())
                            && Objects.equals(accepted.subMerchantId(), paid.subMerchantId())
                            && accepted.orderTotalMinor() == paid.orderTotalMinor(),
                    "POINTS_PAYMENT_ROUTE_MISMATCH");
            var earning =
                    json.readValue(
                            a.snapshot().ruleSnapshot(),
                            PointsOrderPlacementCoordinator.EarningSnapshot.class);
            var actual =
                    DataScopeContext.executeWithoutScope(
                            () ->
                                    items.selectList(
                                            Wrappers.<OrdItem>lambdaQuery()
                                                    .eq(OrdItem::getSubOrderNo, a.subOrderNo())));
            Map<String, OrdItem> byItem = new HashMap<>();
            for (var item : actual)
            if (!Boolean.TRUE.equals(item.getIsGift()))
                require(
                        byItem.put(String.valueOf(item.getId()), item) == null,
                        "POINTS_PAID_LINE_MISMATCH");
            require(byItem.size() == earning.lines().size(), "POINTS_PAID_LINE_MISMATCH");
            Map<String, Long> quantities = new HashMap<>();
            for (var line : earning.lines()) {
                var item = byItem.get(line.itemId());
                require(
                        item != null
                                && item.getQty() != null
                                && item.getQty() == line.quantity()
                                && a.storeNo().equals(item.getSupplyStoreNo())
                                && Objects.equals(item.getPaidShareMinor(), line.goodsCashMinor()),
                        "POINTS_PAID_LINE_MISMATCH");
                quantities.put(line.itemId(), line.quantity());
            }
            var lines =
                    a.snapshot().items().stream()
                            .map(
                                    i ->
                                            new PointsFactPort.LineSnapshot(
                                                    i.itemId(),
                                                    Objects.requireNonNull(
                                                            quantities.get(i.itemId())),
                                                    i.goodsMinor(),
                                                    0))
                            .toList();
            String paidNo = "ORDER:PAID:" + a.subOrderNo();
            var paidFact =
                    PointsLifecycleFacts.sign(
                            new PointsFactPort.LifecycleFact(
                                    paidNo,
                                    null,
                                    a.subOrderNo(),
                                    a.userNo(),
                                    a.typeCode(),
                                    version,
                                    PointsFactPort.FactKind.PAID,
                                    a.snapshot().goodsMinor(),
                                    0,
                                    lines,
                                    at,
                                    paid),
                            json::writeValueAsString);
            facts.append(
                    new PointsOrderEvent(
                            orderNo,
                            a.subOrderNo(),
                            a.merchantNo(),
                            a.storeNo(),
                            a.userNo(),
                            a.typeCode(),
                            a.currency(),
                            version,
                            at,
                            new SourceFactsPort.Fact(0, paidFact, null, null, null, null)));
            if (a.earning()) {
                var grant =
                        new PointsEarnPort.GrantCommand(
                                "ORDER:EARN:" + a.subOrderNo(),
                                a.subOrderNo(),
                                a.userNo(),
                                a.typeCode(),
                                a.storeNo(),
                                paid.channel(),
                                a.scene(),
                                version,
                                earning.lines(),
                                a.merchantNo(),
                                placement.draft().client(),
                                paid);
                facts.append(
                        new PointsOrderEvent(
                                orderNo,
                                a.subOrderNo(),
                                a.merchantNo(),
                                a.storeNo(),
                                a.userNo(),
                                a.typeCode(),
                                a.currency(),
                                version,
                                at,
                                new SourceFactsPort.Fact(0, null, grant, null, null, null)));
            }
        }
    }

    private static void require(boolean valid, String error) {
        if (!valid) throw new IllegalArgumentException(error);
    }
}
