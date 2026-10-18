package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.mapper.TradeMappers.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.refund.policy.AfterSaleWindowPolicy;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

/**
 * A source proof can be emitted only after the original order and all earlier refunds are settled.
 */
@Service
public class OrderPointsClosureFlowServiceImpl implements OrderPointsClosureFlowService {
    private final SubOrderMapper subs;
    private final OrderItemMapper items;
    private final StatusLogMapper logs;
    private final AfterSaleMapper afterSales;
    private final OrderPointsRefundBindingMapper refunds;
    private final RefundExecutionService executions;
    private final OrderPointsBindingService bindings;
    private final OrderPointsFactService facts;
    private final AfterSaleWindowPolicy window;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsClosureFlowServiceImpl(
            SubOrderMapper subs,
            OrderItemMapper items,
            StatusLogMapper logs,
            AfterSaleMapper afterSales,
            OrderPointsRefundBindingMapper refunds,
            RefundExecutionService executions,
            OrderPointsBindingService bindings,
            OrderPointsFactService facts,
            AfterSaleWindowPolicy window) {
        this.subs = subs;
        this.items = items;
        this.logs = logs;
        this.afterSales = afterSales;
        this.refunds = refunds;
        this.executions = executions;
        this.bindings = bindings;
        this.facts = facts;
        this.window = window;
    }

    @Override
    @Transactional("transactionManager")
    public boolean finalizeSubOrder(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var sub = locked(subOrderNo);
                    var binding = bound(sub);
                    if (binding == null) return false;
                    String event = "ORDER:FINALIZED:" + subOrderNo;
                    if (facts.find(event).isPresent()) return true;
                    Long terminalAt = completedAt(subOrderNo);
                    boolean refundedBeforeCompletion =
                            terminalAt == null && OrdSubOrder.REFUNDED.equals(sub.getStatus());
                    if (refundedBeforeCompletion) terminalAt = refundedAt(subOrderNo);
                    if (terminalAt == null
                            || !(OrdSubOrder.COMPLETED.equals(sub.getStatus())
                                    || OrdSubOrder.REFUNDED.equals(sub.getStatus()))
                            || facts.find("ORDER:PAID:" + subOrderNo).isEmpty()
                            || !refundsFinal(subOrderNo)) return false;
                    var original = binding.admission().snapshot().items();
                    var current =
                            items.selectList(
                                    Wrappers.<OrdItem>lambdaQuery()
                                            .eq(OrdItem::getSubOrderNo, subOrderNo));
                    Map<String, OrdItem> byId = new HashMap<>();
                    for (var item : current)
                        if (!Boolean.TRUE.equals(item.getIsGift()))
                            require(
                                    byId.put(String.valueOf(item.getId()), item) == null,
                                    "POINTS_FINAL_LINES_CONFLICT");
                    Map<String, Long> returned = returnedGoods(subOrderNo);
                    require(original.size() == byId.size(), "POINTS_FINAL_LINES_CONFLICT");
                    var lines = new ArrayList<PointsFactPort.LineSnapshot>();
                    long goods = 0, refunded = 0;
                    for (var line : original) {
                        var item = byId.get(line.itemId());
                        require(
                                item != null
                                        && item.getQty() != null
                                        && item.getQty() > 0
                                        && binding.admission()
                                                .storeNo()
                                                .equals(item.getSupplyStoreNo()),
                                "POINTS_FINAL_LINES_CONFLICT");
                        long returnedMinor = returned.getOrDefault(line.itemId(), 0L);
                        require(
                                returnedMinor >= 0 && returnedMinor <= line.goodsMinor(),
                                "POINTS_FINAL_LINES_CONFLICT");
                        lines.add(
                                new PointsFactPort.LineSnapshot(
                                        line.itemId(),
                                        item.getQty(),
                                        line.goodsMinor(),
                                        returnedMinor));
                        goods = Math.addExact(goods, line.goodsMinor());
                        refunded = Math.addExact(refunded, returnedMinor);
                    }
                    require(
                            goods == binding.admission().snapshot().goodsMinor(),
                            "POINTS_FINAL_LINES_CONFLICT");
                    if (refundedBeforeCompletion && refunded != goods) return false;
                    long version = sub.getVersion() == null ? 0 : sub.getVersion();
                    Instant at = Instant.ofEpochMilli(terminalAt);
                    var signed =
                            PointsLifecycleFacts.sign(
                                    new PointsFactPort.LifecycleFact(
                                            event,
                                            null,
                                            subOrderNo,
                                            sub.getUserNo(),
                                            binding.admission().typeCode(),
                                            version,
                                            PointsFactPort.FactKind.FINALIZED,
                                            goods,
                                            refunded,
                                            lines,
                                            at),
                                    json::writeValueAsString);
                    facts.append(
                            envelope(
                                    binding,
                                    sub,
                                    version,
                                    at,
                                    new SourceFactsPort.Fact(0, signed, null, null, null, null)));
                    return true;
                });
    }

    @Override
    @Transactional("transactionManager")
    public boolean closeRefundResponsibility(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var sub = locked(subOrderNo);
                    var binding = bound(sub);
                    if (binding == null) return false;
                    String event = "ORDER:REFUND_CLOSED:" + subOrderNo;
                    if (facts.find(event).isPresent()) return true;
                    var finalized = facts.find("ORDER:FINALIZED:" + subOrderNo);
                    if (finalized.isEmpty() || !refundsFinal(subOrderNo)) return false;
                    long terminalAt = finalized.orElseThrow().occurredAt().toEpochMilli();
                    Long standard = window.deadlineAt(terminalAt, OrdAfterSale.REASON_OTHER);
                    Long quality = window.deadlineAt(terminalAt, OrdAfterSale.REASON_QUALITY);
                    if (standard == null || quality == null) return false;
                    long deadline = Math.max(standard, quality);
                    long now = System.currentTimeMillis();
                    if (now <= deadline) return false;
                    long version = sub.getVersion() == null ? 0 : sub.getVersion();
                    Instant at = Instant.ofEpochMilli(now);
                    var closure =
                            new PointsFactPort.RefundClosureFact(
                                    event,
                                    binding.admission().typeCode(),
                                    subOrderNo,
                                    sub.getUserNo(),
                                    version,
                                    Instant.ofEpochMilli(deadline),
                                    true,
                                    true,
                                    at);
                    facts.append(
                            envelope(
                                    binding,
                                    sub,
                                    version,
                                    at,
                                    new SourceFactsPort.Fact(0, null, null, closure, null, null)));
                    return true;
                });
    }

    private OrdSubOrder locked(String no) {
        var sub =
                subs.selectOne(
                        Wrappers.<OrdSubOrder>lambdaQuery()
                                .eq(OrdSubOrder::getSubOrderNo, no)
                                .last("FOR UPDATE"));
        require(sub != null, "POINTS_CLOSURE_SUB_UNKNOWN");
        return sub;
    }

    private OrderPointsBindingService.Binding bound(OrdSubOrder sub) {
        var binding = bindings.find(sub.getSubOrderNo());
        if (binding == null) return null;
        require(
                binding.phase() == OrderPointsBindingService.Phase.BOUND
                        && binding.admission().orderNo().equals(sub.getOrderNo())
                        && binding.admission().storeNo().equals(sub.getStoreNo())
                        && binding.admission().merchantNo().equals(sub.getEntityNo())
                        && binding.admission().userNo().equals(sub.getUserNo()),
                "POINTS_CLOSURE_BINDING_CONFLICT");
        return binding;
    }

    private Long completedAt(String no) {
        return statusAt(no, OrdSubOrder.COMPLETED);
    }

    private Long refundedAt(String no) {
        return statusAt(no, OrdSubOrder.REFUNDED);
    }

    private Long statusAt(String no, String status) {
        return logs
                .selectList(
                        Wrappers.<OrdStatusLog>lambdaQuery()
                                .eq(OrdStatusLog::getSubOrderNo, no)
                                .eq(OrdStatusLog::getStatus, status))
                .stream()
                .map(OrdStatusLog::getAt)
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
    }

    private boolean refundsFinal(String no) {
        var all =
                afterSales.selectList(
                        Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSubOrderNo, no));
        var completed = new HashSet<String>();
        for (var as : all) {
            if (OrdAfterSale.REFUNDED.equals(as.getStatus())) {
                completed.add(as.getAfterSaleNo());
                var execution = executions.inspect(as.getAfterSaleNo());
                var binding = refunds.find(as.getAfterSaleNo());
                if (execution == null
                        || !"COMMITTED".equals(execution.preparation().state())
                        || binding == null
                        || !"COMMITTED".equals(binding.get("phase"))) return false;
            } else if (!OrdAfterSale.REJECTED.equals(as.getStatus())
                    && !OrdAfterSale.CLOSED.equals(as.getStatus())) return false;
        }
        for (var row : refunds.forSubOrder(no)) {
            if (closedAbortedRefund(row)) continue;
            if (!"COMMITTED".equals(row.get("phase"))
                    || !completed.contains(row.get("after_sale_no"))) return false;
        }
        return true;
    }

    private boolean closedAbortedRefund(Map<String, Object> row) {
        if (!"ABORTED".equals(row.get("phase"))) return false;
        String no = (String) row.get("after_sale_no");
        var as = afterSales.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, no));
        var execution = executions.inspect(no);
        return as != null && OrdAfterSale.CLOSED.equals(as.getStatus())
                && Objects.equals(as.getSubOrderNo(), row.get("sub_order_no"))
                && execution != null && execution.preparation() != null
                && "ABORTED".equals(execution.preparation().state())
                && Objects.equals(execution.preparation().subOrderNo(), row.get("sub_order_no"));
    }

    private Map<String, Long> returnedGoods(String no) {
        Map<String, Long> result = new HashMap<>();
        for (var row : refunds.forSubOrder(no)) {
            if (closedAbortedRefund(row)) continue;
            require("COMMITTED".equals(row.get("phase")), "POINTS_REFUND_SOURCE_BUSY");
            var command =
                    json.readValue(
                            (String) row.get("command_json"), PointsRefundPort.RefundCommand.class);
            for (var line : command.lines())
                result.merge(line.itemId(), line.cumulativeGoodsMinor(), Math::max);
        }
        return result;
    }

    private PointsOrderEvent envelope(
            OrderPointsBindingService.Binding binding,
            OrdSubOrder sub,
            long version,
            Instant at,
            SourceFactsPort.Fact fact) {
        var a = binding.admission();
        return new PointsOrderEvent(
                a.orderNo(),
                sub.getSubOrderNo(),
                a.merchantNo(),
                a.storeNo(),
                a.userNo(),
                a.typeCode(),
                a.currency(),
                version,
                at,
                fact);
    }

    private static void require(boolean yes, String code) {
        if (!yes) throw new IllegalArgumentException(code);
    }
}
