package ai.neargo.shop.trade.order.printing.service.impl;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.order.printing.*;
import ai.neargo.shop.trade.order.printing.service.OrderPrintFactService;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService.ConfirmedPayment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Service
public class OrderPrintFactServiceImpl implements OrderPrintFactService {
    private final OrderPrintFactMapper facts;
    private final StoreOrderMapper subs;
    private final PaymentMapper orders;
    private final OrderPrintFactRecorder recorder;
    private final OutboxEventBus events;
    private final ObjectMapper json;

    public OrderPrintFactServiceImpl(
            OrderPrintFactMapper f,
            StoreOrderMapper s,
            PaymentMapper o,
            OrderPrintFactRecorder r,
            OutboxEventBus e,
            ObjectMapper j) {
        facts = f;
        subs = s;
        orders = o;
        recorder = r;
        events = e;
        json = j;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PrintFactRef> recordPaid(String no, ConfirmedPayment confirmed) {
        var result = new ArrayList<PrintFactRef>();
        for (var sub :
                subs.selectList(
                        new QueryWrapper<OrdSubOrder>()
                                .eq("order_no", no)
                                .orderByAsc("sub_order_no"))) {
            var existing =
                    facts.selectList(
                            new QueryWrapper<OrderPrintFact>()
                                    .eq("order_no", no)
                                    .eq("sub_order_no", sub.getSubOrderNo()));
            if (!existing.isEmpty()) {
                result.add(ref(existing.getFirst()));
                continue;
            }
            var fact = recorder.capturePaidSubOrder(no, sub.getSubOrderNo(), confirmed);
            facts.insert(fact);
            var ref = ref(fact);
            events.publish(new OrderPrintFactReady(no, sub.getSubOrderNo(), ref));
            result.add(ref);
        }
        return List.copyOf(result);
    }

    public List<PrintFactRef> referencesByOrder(PrintScope s, String no) {
        return facts
                .selectList(
                        scoped(s)
                                .and(q -> q.eq("order_no", no).or().eq("sub_order_no", no))
                                .orderByAsc("id")
                                .last("LIMIT 101"))
                .stream()
                .map(this::ref)
                .toList();
    }

    private QueryWrapper<OrderPrintFact> scoped(PrintScope s) {
        return new QueryWrapper<OrderPrintFact>()
                .eq("tenant_no", s.tenantNo())
                .eq("entity_no", s.entityNo())
                .eq("store_no", s.storeNo());
    }

    private PrintFactRef ref(OrderPrintFact f) {
        return new PrintFactRef(
                f.getTenantNo(),
                f.getEntityNo(),
                f.getStoreNo(),
                "SUB_ORDER",
                f.getPrintFactNo(),
                f.getBizRevision(),
                f.getScene(),
                f.getSnapshotHash());
    }

    public PrintFactRef resolveReference(PrintSourceReference r, PrintScope s) {
        if (!"SUB_ORDER".equals(r.type()) || !"ORDER_PAID".equals(r.scene()))
            throw new PrintFailure("print.sourceMismatch");
        var f =
                facts.selectOne(
                        scoped(s)
                                .and(w -> w.eq("sub_order_no", r.no()).or().eq("order_no", r.no()))
                                .eq("print_fact_no", r.factNo())
                                .eq("biz_revision", r.revision())
                                .eq("scene", r.scene()));
        if (f == null) throw new PrintFailure("print.factNotFound");
        return ref(f);
    }

    public PrintFact snapshot(PrintFactRef r) {
        var f =
                facts.selectOne(
                        scoped(new PrintScope(r.tenantNo(), r.entityNo(), r.storeNo()))
                                .eq("print_fact_no", r.factNo()));
        if (f == null || !ref(f).equals(r)) throw new PrintFailure("print.factNotFound");
        return new PrintFact(
                r,
                f.getOrderNo(),
                f.getSubOrderNo(),
                f.getTriggerType(),
                f.getSourceFactKey(),
                f.getSourceOccurredAt().toInstant(ZoneOffset.UTC),
                f.getPayloadSchemaVersion(),
                new FrozenJson(f.getSnapshot(), f.getSnapshotHash()),
                Printability.valueOf(f.getPrintability()),
                f.getBlockCode(),
                Arrays.asList(json.readValue(f.getDiagnostics(), String[].class)));
    }

    public PrintFactPage scan(PrintFactCursor c, int limit) {
        if (limit < 1
                || limit > 500
                || c.from() == null
                || c.to() == null
                || c.from().isAfter(c.to())) throw new PrintFailure("print.invalidScan");
        var q =
                scoped(c.scope())
                        .ge("created_at", LocalDateTime.ofInstant(c.from(), ZoneOffset.UTC))
                        .le("created_at", LocalDateTime.ofInstant(c.to(), ZoneOffset.UTC));
        if (c.watermarkAt() != null)
            q.and(
                    w ->
                            w.gt(
                                            "created_at",
                                            LocalDateTime.ofInstant(
                                                    c.watermarkAt(), ZoneOffset.UTC))
                                    .or(
                                            w2 ->
                                                    w2.eq(
                                                                    "created_at",
                                                                    LocalDateTime.ofInstant(
                                                                            c.watermarkAt(),
                                                                            ZoneOffset.UTC))
                                                            .gt("print_fact_no", c.watermarkNo())));
        var rows =
                facts.selectList(
                        q.orderByAsc("created_at", "print_fact_no").last("LIMIT " + limit));
        var last = rows.isEmpty() ? null : rows.getLast();
        var next =
                last == null
                        ? c
                        : new PrintFactCursor(
                                c.scope(),
                                c.sourceKind(),
                                c.from(),
                                c.to(),
                                last.getCreatedAt().toInstant(ZoneOffset.UTC),
                                last.getPrintFactNo());
        return new PrintFactPage(rows.stream().map(this::ref).toList(), next, rows.size() == limit);
    }

    public PrintExecutionGuard executionGuard(PrintFactRef ref) {
        var fact = snapshot(ref);
        var sub =
                subs.selectOne(
                        new QueryWrapper<OrdSubOrder>()
                                .eq("tenant_no", ref.tenantNo())
                                .eq("entity_no", ref.entityNo())
                                .eq("store_no", ref.storeNo())
                                .eq("sub_order_no", fact.subOrderNo()));
        if (sub == null)
            return new PrintExecutionGuard(
                    false, GuardDecision.HOLD_UNKNOWN, ref.bizRevision(), false, "SOURCE_MISSING");
        return new PrintExecutionGuard(
                true,
                GuardDecision.ALLOW_HISTORICAL_RECEIPT,
                String.valueOf(sub.getVersion()),
                OrdSubOrder.CANCELLED.equals(sub.getStatus()),
                "HISTORICAL_PAID_RECEIPT");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordControl(
            String orderNo, String subNo, String type, String controlNo, Instant occurredAt) {
        var rows =
                facts.selectList(
                        new QueryWrapper<OrderPrintFact>()
                                .eq("order_no", orderNo)
                                .eq(subNo != null, "sub_order_no", subNo));
        for (var row : rows) {
            var ref = ref(row);
            events.publish(
                    "PC" + PrintHashes.sha256(controlNo + ":" + ref.factNo()).substring(0, 28),
                    new OrderPrintControlChanged(
                            new PrintControlFact(
                                    new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo()),
                                    orderNo,
                                    row.getSubOrderNo(),
                                    controlNo,
                                    type,
                                    row.getBizRevision(),
                                    occurredAt,
                                    List.of(ref))));
        }
    }
}
