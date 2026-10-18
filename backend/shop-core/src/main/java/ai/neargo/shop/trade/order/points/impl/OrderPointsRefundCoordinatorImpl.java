package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort.VerifiedPayment;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.mapper.TradeMappers.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class OrderPointsRefundCoordinatorImpl implements OrderPointsRefundCoordinator {
    private final OrderPointsRefundBindingMapper mapper;
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final OrderItemMapper items;
    private final AfterSaleMapper afterSales;
    private final OrderPointsBindingService bindings;
    private final RefundExecutionService executions;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsRefundCoordinatorImpl(
            OrderPointsRefundBindingMapper mapper,
            OrderMapper orders,
            SubOrderMapper subs,
            OrderItemMapper items,
            AfterSaleMapper afterSales,
            OrderPointsBindingService bindings,
            RefundExecutionService executions) {
        this.mapper = mapper;
        this.orders = orders;
        this.subs = subs;
        this.items = items;
        this.afterSales = afterSales;
        this.bindings = bindings;
        this.executions = executions;
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding stage(String afterSaleNo, RefundPlan plan, VerifiedPayment payment) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    require(
                            plan != null && payment != null && afterSaleNo != null,
                            "POINTS_REFUND_SOURCE_INVALID");
                    var sub =
                            subs.selectOne(
                                    Wrappers.<OrdSubOrder>lambdaQuery()
                                            .eq(OrdSubOrder::getSubOrderNo, plan.subOrderNo())
                                            .last("FOR UPDATE"));
                    require(sub != null, "POINTS_REFUND_SOURCE_UNKNOWN");
                    var prior = decode(mapper.lock(afterSaleNo));
                    if (prior != null) {
                        require(
                                prior.subOrderNo().equals(plan.subOrderNo()),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        require(prior.command().payment().equals(payment.forSubOrder(plan.subOrderNo())),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        var execution = executions.inspect(afterSaleNo);
                        require(
                                execution != null && plan.equals(execution.plan()),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        return prior;
                    }
                    var as =
                            afterSales.selectOne(
                                    Wrappers.<OrdAfterSale>lambdaQuery()
                                            .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                                            .last("FOR UPDATE"));
                    var root =
                            orders.selectOne(
                                    Wrappers.<OrdOrder>lambdaQuery()
                                            .eq(OrdOrder::getOrderNo, sub.getOrderNo())
                                            .last("FOR UPDATE"));
                    require(
                            as != null
                                    && root != null
                                    && Boolean.TRUE.equals(root.getPointsIntegrated())
                                    && OrdOrder.PAID.equals(root.getStatus())
                                    && OrdAfterSale.REFUNDING.equals(as.getStatus())
                                    && afterSaleNo.equals(as.getAfterSaleNo())
                                    && plan.subOrderNo().equals(as.getSubOrderNo())
                                    && sub.getOrderNo().equals(payment.orderNo())
                                    && root.getUserNo().equals(payment.userNo())
                                    && root.getCurrency().equals(payment.currency())
                                    && Objects.equals(root.getPayAmount(), payment.amountMinor()),
                            "POINTS_REFUND_SOURCE_CONFLICT");
                    var execution = executions.inspect(afterSaleNo);
                    require(
                            execution != null && plan.equals(execution.plan()),
                            "POINTS_REFUND_SOURCE_CONFLICT");
                    var binding = bindings.find(plan.subOrderNo());
                    require(
                            binding != null
                                    && binding.phase() == OrderPointsBindingService.Phase.BOUND
                                    && binding.admission().orderNo().equals(root.getOrderNo())
                                    && binding.admission().storeNo().equals(sub.getStoreNo())
                                    && binding.admission().merchantNo().equals(sub.getEntityNo()),
                            "POINTS_REFUND_BINDING_MISSING");
                    var frozen = binding.snapshot().payment();
                    var paid = payment.forSubOrder(plan.subOrderNo());
                    require(
                            frozen.fundsPath().equals(paid.fundsPath())
                                    && frozen.channel().equals(paid.channel())
                                    && frozen.merchantNo().equals(paid.merchantNo())
                                    && frozen.storeNo().equals(paid.storeNo())
                                    && frozen.accountVersion().equals(paid.accountVersion())
                                    && Objects.equals(frozen.subMerchantId(), paid.subMerchantId())
                                    && frozen.orderTotalMinor() == paid.orderTotalMinor(),
                            "POINTS_REFUND_PAYMENT_MISMATCH");
                    var actual =
                            items.selectList(
                                    Wrappers.<OrdItem>lambdaQuery()
                                            .eq(OrdItem::getSubOrderNo, plan.subOrderNo()));
                    Map<String, OrdItem> byItem = new HashMap<>();
                    for (var item : actual)
                        if (!Boolean.TRUE.equals(item.getIsGift()))
                            require(
                                    byItem.put(String.valueOf(item.getId()), item) == null,
                                    "POINTS_REFUND_LINES_INVALID");
                    Map<String, Long> original = new HashMap<>();
                    for (var item : binding.snapshot().items())
                        require(
                                original.put(item.itemId(), item.goodsMinor()) == null,
                                "POINTS_REFUND_LINES_INVALID");
                    require(
                            byItem.keySet().equals(original.keySet()),
                            "POINTS_REFUND_LINES_INVALID");
                    Map<String, Long> newCash = new HashMap<>();
                    for (var line : plan.lines())
                        require(
                                line.amountMinor() >= 0
                                        && newCash.put(
                                                        String.valueOf(line.itemId()),
                                                        line.amountMinor())
                                                == null
                                        && byItem.containsKey(String.valueOf(line.itemId())),
                                "POINTS_REFUND_LINES_INVALID");
                    List<PointsRefundPort.RefundLine> lines = new ArrayList<>();
                    long cumulative = 0;
                    for (var id : original.keySet().stream().sorted().toList()) {
                        var item = byItem.get(id);
                        require(item.getQty() != null && item.getQty() > 0,
                                "POINTS_REFUND_LINES_INVALID");
                        long cashOriginal = value(item.getPaidShareMinor());
                        long refunded =
                                Math.addExact(
                                        value(item.getRefundedMinor()),
                                        newCash.getOrDefault(id, 0L));
                        require(refunded <= cashOriginal, "POINTS_REFUND_CAPACITY_EXCEEDED");
                        long gross = original.get(id);
                        long share =
                                cashOriginal > 0
                                        ? Math.multiplyExact(gross, refunded) / cashOriginal
                                        : Math.multiplyExact(
                                                        gross,
                                                        value(item.getRefundedQty())
                                                                + plan.lines().stream()
                                                                        .filter(
                                                                                l ->
                                                                                        id.equals(
                                                                                                String
                                                                                                        .valueOf(
                                                                                                                l
                                                                                                                        .itemId())))
                                                                        .mapToLong(l -> l.qty())
                                                                        .sum())
                                                / item.getQty();
                        require(share <= gross, "POINTS_REFUND_CAPACITY_EXCEEDED");
                        lines.add(new PointsRefundPort.RefundLine(id, share));
                        cumulative = Math.addExact(cumulative, share);
                    }
                    long sourceVersion = as.getVersion() == null ? 0 : as.getVersion();
                    var command =
                            new PointsRefundPort.RefundCommand(
                                    afterSaleNo,
                                    plan.subOrderNo(),
                                    sub.getUserNo(),
                                    binding.admission().typeCode(),
                                    root.getCurrency(),
                                    sourceVersion,
                                    plan.totalMinor(),
                                    cumulative,
                                    lines,
                                    paid);
                    mapper.create(
                            afterSaleNo,
                            plan.subOrderNo(),
                            sourceVersion,
                            json.writeValueAsString(command));
                    return new Binding(
                            afterSaleNo, plan.subOrderNo(), Phase.STAGED, 0, command, null, null);
                });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public Binding inspect(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> decode(mapper.find(afterSaleNo)));
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public List<String> pending(int limit) {
        require(limit >= 1 && limit <= 200, "POINTS_REFUND_LIMIT_INVALID");
        return DataScopeContext.executeWithoutScope(() -> mapper.pending(limit));
    }

    private Binding decode(Map<String, Object> row) {
        if (row == null) return null;
        return new Binding(
                (String) row.get("after_sale_no"),
                (String) row.get("sub_order_no"),
                Phase.valueOf((String) row.get("phase")),
                ((Number) row.get("version")).longValue(),
                json.readValue(
                        (String) row.get("command_json"), PointsRefundPort.RefundCommand.class),
                row.get("preparation_json") == null
                        ? null
                        : json.readValue(
                                (String) row.get("preparation_json"),
                                PointsRefundPort.RefundPreparation.class),
                (String) row.get("cash_payment_no"));
    }

    private static long value(Long x) {
        return x == null ? 0 : x;
    }

    private static long value(Integer x) {
        return x == null ? 0 : x;
    }

    private static void require(boolean yes, String code) {
        if (!yes) throw new IllegalArgumentException(code);
    }
}
