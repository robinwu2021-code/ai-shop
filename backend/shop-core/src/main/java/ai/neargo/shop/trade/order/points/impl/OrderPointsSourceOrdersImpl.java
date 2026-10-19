package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.machine.payment.PaymentContext;
import ai.neargo.shop.trade.order.machine.payment.PaymentEvent;
import ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The only bridge from a confirmed placement to the existing order and payment state. */
@Service
public class OrderPointsSourceOrdersImpl implements PointsOrderPlacementCoordinator.SourceOrders {
    private final PaymentMapper orders;
    private final StoreOrderMapper subs;
    private final OrderItemMapper items;
    private final TradeStatusWriter statuses;
    private final OutboxEventBus events;

    public OrderPointsSourceOrdersImpl(
            PaymentMapper orders,
            StoreOrderMapper subs,
            OrderItemMapper items,
            TradeStatusWriter statuses,
            OutboxEventBus events) {
        this.orders = orders;
        this.subs = subs;
        this.items = items;
        this.statuses = statuses;
        this.events = events;
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public PointsOrderPlacementCoordinator.SourceOrder lock(String orderNo) {
        require(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "POINTS_SOURCE_TRANSACTION_REQUIRED");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var order =
                            orders.selectOne(
                                    Wrappers.<OrdOrder>lambdaQuery()
                                            .eq(OrdOrder::getOrderNo, orderNo)
                                            .last("FOR UPDATE"));
                    return order == null
                            ? null
                            : new PointsOrderPlacementCoordinator.SourceOrder(
                                    order.getStatus(),
                                    order.getUserNo(),
                                    order.getCurrency(),
                                    value(order.getPayAmount()),
                                    value(order.getVersion()),
                                    order.getUpdatedAt() == null
                                            ? java.time.Instant.EPOCH
                                            : order.getUpdatedAt()
                                                    .atZone(ZoneId.systemDefault())
                                                    .toInstant());
                });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public void finish(
            PointsOrderPlacementCoordinator.Draft draft, PointsRedeemPort.HoldOutcome outcome) {
        require(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "POINTS_SOURCE_TRANSACTION_REQUIRED");
        DataScopeContext.executeWithoutScope(
                () -> {
                    var root =
                            orders.selectOne(
                                    Wrappers.<OrdOrder>lambdaQuery()
                                            .eq(OrdOrder::getOrderNo, draft.orderNo())
                                            .last("FOR UPDATE"));
                    require(
                            root != null
                                    && OrdOrder.POINTS_PENDING.equals(root.getStatus())
                                    && Boolean.TRUE.equals(root.getPointsIntegrated())
                                    && draft.userNo().equals(root.getUserNo())
                                    && draft.currency().equals(root.getCurrency())
                                    && value(root.getPayAmount()) == draft.basePayMinor(),
                            "POINTS_PLACEMENT_SOURCE_CONFLICT");

                    List<OrdSubOrder> children =
                            subs.selectList(
                                    Wrappers.<OrdSubOrder>lambdaQuery()
                                            .eq(OrdSubOrder::getOrderNo, draft.orderNo()));
                    require(
                            children.size() == draft.allSubOrderNos().size(),
                            "POINTS_SOURCE_CHILDREN_CHANGED");
                    Map<String, OrdSubOrder> bySub = new HashMap<>();
                    for (var child : children) {
                        require(
                                bySub.put(child.getSubOrderNo(), child) == null,
                                "POINTS_SOURCE_CHILDREN_CHANGED");
                    }
                    require(
                            bySub.keySet().equals(new HashSet<>(draft.allSubOrderNos())),
                            "POINTS_SOURCE_CHILDREN_CHANGED");

                    Map<String, PointsRedeemPort.Share> byShare = new HashMap<>();
                    long totalDeduct = 0;
                    for (var share : outcome.shares()) {
                        require(
                                share != null
                                        && byShare.put(share.subOrderNo(), share) == null
                                        && share.minor() >= 0
                                        && share.points() >= 0,
                                "POINTS_SOURCE_SHARES_INVALID");
                        totalDeduct = Math.addExact(totalDeduct, share.minor());
                    }
                    require(
                            totalDeduct == outcome.minor() && totalDeduct <= draft.basePayMinor(),
                            "POINTS_SOURCE_SHARES_INVALID");
                    Set<String> participating = new HashSet<>();
                    for (var candidate : draft.candidates()) {
                        var target = candidate.target().target();
                        var child = bySub.get(target.subOrderNo());
                        require(
                                child != null
                                        && participating.add(target.subOrderNo())
                                        && OrdSubOrder.WAIT_PAY.equals(child.getStatus())
                                        && target.merchantNo().equals(child.getEntityNo())
                                        && target.storeNo().equals(child.getStoreNo())
                                        && value(child.getPayAmount())
                                                == target.goodsMinor()
                                                        + value(child.getFreightAmount()),
                                "POINTS_SOURCE_CHILDREN_CHANGED");
                        var share = byShare.get(target.subOrderNo());
                        long deduction = share == null ? 0 : share.minor();
                        if (share != null) {
                            require(
                                    target.merchantNo().equals(share.merchantNo())
                                            && target.storeNo().equals(share.storeNo())
                                            && share.minor() <= target.goodsMinor()
                                            && share.points() <= Integer.MAX_VALUE,
                                    "POINTS_SOURCE_SHARES_INVALID");
                        }

                        List<OrdItem> lines =
                                items.selectList(
                                        Wrappers.<OrdItem>lambdaQuery()
                                                .eq(OrdItem::getSubOrderNo, target.subOrderNo()));
                        Map<String, OrdItem> paid = new HashMap<>();
                        for (var line : lines) {
                            if (!Boolean.TRUE.equals(line.getIsGift())) {
                                require(
                                        paid.put(String.valueOf(line.getId()), line) == null,
                                        "POINTS_SOURCE_LINES_CHANGED");
                            }
                        }
                        require(
                                paid.size() == target.items().size(),
                                "POINTS_SOURCE_LINES_CHANGED");
                        Map<String, ai.neargo.shop.spi.points.PointsEarnPort.EarnLine>
                                acceptedRules = new HashMap<>();
                        for (var rule : candidate.rules()) {
                            require(
                                    acceptedRules.put(rule.itemId(), rule) == null,
                                    "POINTS_SOURCE_LINES_CHANGED");
                        }
                        require(acceptedRules.size() == paid.size(), "POINTS_SOURCE_LINES_CHANGED");
                        long[] after =
                                LineShares.allocate(
                                        target.goodsMinor() - deduction,
                                        target.items().stream()
                                                .mapToLong(PointsRedeemPort.Item::goodsMinor)
                                                .toArray());
                        for (int i = 0; i < target.items().size(); i++) {
                            var accepted = target.items().get(i);
                            var line = paid.get(accepted.itemId());
                            var rule = acceptedRules.get(accepted.itemId());
                            require(
                                    line != null
                                            && accepted.goodsMinor() >= 0
                                            && rule != null
                                            && rule.goodsNo().equals(line.getGoodsNo())
                                            && rule.quantity() == line.getQty()
                                            && target.storeNo().equals(line.getSupplyStoreNo())
                                            && value(line.getPaidShareMinor())
                                                    == accepted.goodsMinor(),
                                    "POINTS_SOURCE_LINES_CHANGED");
                            line.setPaidShareMinor(after[i]);
                            require(items.updateById(line) == 1, "POINTS_SOURCE_LINES_CHANGED");
                        }
                        child.setPointsDeduct((int) (share == null ? 0 : share.points()));
                        child.setPointsDeductMinor(deduction);
                        child.setPayAmount(value(child.getPayAmount()) - deduction);
                        require(subs.updateById(child) == 1, "POINTS_SOURCE_CHILDREN_CHANGED");
                    }
                    require(
                            participating.containsAll(byShare.keySet()),
                            "POINTS_SOURCE_SHARES_INVALID");

                    statuses.payment(
                            root,
                            PaymentEvent.POINTS_READY,
                            Actor.SYSTEM,
                            null,
                            "积分受理完成",
                            new PaymentContext(
                                    PayModes.OFFLINE.equals(draft.scene()), true, true, false));
                    root.setPayAmount(draft.basePayMinor() - outcome.minor());
                    require(orders.updateById(root) == 1, "POINTS_PLACEMENT_SOURCE_CONFLICT");
                    events.publish(
                            new OrderEvents.OrderCreated(
                                    draft.orderNo(),
                                    draft.userNo(),
                                    draft.allSubOrderNos(),
                                    root.getPayAmount()));
                    return null;
                });
    }

    private static long value(Long n) {
        return n == null ? 0 : n;
    }

    private static void require(boolean ok, String code) {
        if (!ok) throw new IllegalArgumentException(code);
    }
}
