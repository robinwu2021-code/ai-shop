package ai.neargo.commerce.persistence;

import static ai.neargo.commerce.core.mall.RefundSnapshot.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.VerifiedPaymentFact;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.Period;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import javax.sql.DataSource;

/** Refund rows, original source cuts and provenance reversals share the existing commerce UoW. */
public final class JdbcRefundRepository implements RefundRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final JdbcSubscriptionRepository subscriptions;

    public JdbcRefundRepository(DataSource ds, CommerceIdGenerator ids) {
        this.ds = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.subscriptions = new JdbcSubscriptionRepository(ds, ids);
    }

    private void guard(String tenant) {
        JdbcWriteGuard.require(ds, tenant);
    }

    private String encode(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private <T> T decode(String raw, Class<T> type) {
        return raw == null ? null : json.readValue(raw, type);
    }

    private static void changed(int rows) {
        if (rows != 1) throw new MallException("VERSION_CONFLICT");
    }

    public Optional<State> find(String tenant, String no) {
        guard(tenant);
        return jdbc
                .query(
                        "SELECT * FROM mall_refund WHERE tenant_no=? AND refund_no=? FOR UPDATE",
                        (r, n) ->
                                new State(
                                        decode(r.getString("policy_snapshot"), Preview.class),
                                        r.getString("status"),
                                        r.getLong("approved_cash_minor"),
                                        r.getLong("restore_credit_minor"),
                                        r.getString("reviewed_by"),
                                        r.getString("reviewed_by") == null
                                                ? r.getString("reason")
                                                : r.getString("review_evidence"),
                                        decode(
                                                r.getString("command_snapshot"),
                                                PaymentRefundCommand.class),
                                        decode(
                                                r.getString("verified_fact_snapshot"),
                                                VerifiedRefundFact.class),
                                        r.getObject("reviewed_at", Long.class)),
                        tenant,
                        no)
                .stream()
                .findFirst();
    }

    public Optional<State> latestLatePayment(String tenant, String orderNo, String prefix) {
        guard(tenant);
        var rows =
                jdbc.queryForList(
                        "SELECT refund_no FROM mall_refund WHERE tenant_no=? AND order_no=? AND"
                                + " refund_no LIKE ? ORDER BY id DESC LIMIT 1 FOR UPDATE",
                        String.class,
                        tenant,
                        orderNo,
                        prefix + "-%");
        return rows.isEmpty() ? Optional.empty() : find(tenant, rows.getFirst());
    }

    public Payment payment(MallSnapshot.Order order) {
        var s = order.quote().store();
        guard(s.tenantNo());
        var facts =
                jdbc.query(
                        "SELECT payment_attempt_no,verified_fact_snapshot FROM mall_payment_ref"
                            + " WHERE tenant_no=? AND order_no=? AND status='SUCCEEDED' FOR UPDATE",
                        (r, n) ->
                                new Payment(
                                        r.getString(1),
                                        decode(r.getString(2), VerifiedPaymentFact.class)),
                        s.tenantNo(),
                        order.orderNo());
        if (facts.size() != 1 || facts.getFirst().fact() == null)
            throw new MallException("PAYMENT_PROOF_REQUIRED");
        var result = facts.getFirst();
        var f = result.fact();
        if (!s.tenantNo().equals(f.tenantNo())
                || !order.orderNo().equals(f.orderNo())
                || !f.paid().currency().equals(order.quote().currency())
                || f.paid().minor() != order.quote().deal() - order.quote().credit()
                || f.verificationReference() == null
                || f.verificationReference().isBlank())
            throw new MallException("PAYMENT_PROOF_REQUIRED");
        return result;
    }

    public List<OwnedSource> ownedSources(MallSnapshot.Order o) {
        var store = o.quote().store();
        guard(store.tenantNo());
        var periods =
                subscriptions.periods(store).stream()
                        .collect(java.util.stream.Collectors.toMap(Period::no, p -> p));
        return jdbc.query(
                "SELECT p.period_no,p.version,c.order_component_no,i.sku_snapshot FROM"
                    + " mch_subscription_period p JOIN mall_fulfillment_task t ON"
                    + " t.tenant_no=p.tenant_no AND t.task_no=p.source_ref_no AND"
                    + " t.task_type='DIGITAL' JOIN mall_order_component c ON"
                    + " c.tenant_no=t.tenant_no AND c.order_component_no=t.order_component_no JOIN"
                    + " mall_order_item i ON i.tenant_no=c.tenant_no AND"
                    + " i.order_item_no=c.order_item_no WHERE p.tenant_no=? AND p.store_no=? AND"
                    + " i.order_no=? ORDER BY p.delivery_seq,p.id FOR SHARE",
                (r, n) -> {
                    var p = periods.get(r.getString(1));
                    if (p == null) throw new MallException("SOURCE_EVIDENCE_MISSING");
                    var line = decode(r.getString(4), MallSnapshot.Line.class);
                    return new OwnedSource(
                            p,
                            r.getLong(2),
                            r.getString(3),
                            line.sku().refundPolicySnapshot(),
                            new JdbcCreditProvenance(ds).source(store, p.no(), p.credit()));
                },
                store.tenantNo(),
                store.storeNo(),
                o.orderNo());
    }

    public List<PendingTask> pendingTasks(MallSnapshot.Order o) {
        var store = o.quote().store();
        guard(store.tenantNo());
        var components =
                new JdbcMallRepository(ds, new JdbcCommerceEventPersistence(ds))
                        .components(store, o.orderNo());
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM mall_fulfillment_task WHERE tenant_no=? AND order_no=? AND"
                                + " task_type='DIGITAL' ORDER BY task_no FOR UPDATE",
                        store.tenantNo(),
                        o.orderNo());
        var expected = new TreeMap<String, PendingTask>();
        for (var c : components) {
            if (c.snapshot().allocation().component().kind() == ComponentKind.HARDWARE) continue;
            if (!"PENDING".equals(c.fulfillmentStatus()))
                throw new MallException("INVALID_TASK_GROUP");
            var line =
                    o.quote().lines().stream()
                            .filter(l -> l.components().contains(c.snapshot()))
                            .findFirst()
                            .orElseThrow(() -> new MallException("INVALID_TASK_GROUP"));
            for (var cycle : c.snapshot().allocation().cycles()) {
                String no = c.orderComponentNo() + ":" + cycle.sequence();
                var row =
                        rows.stream()
                                .filter(v -> no.equals(v.get("task_no")))
                                .findFirst()
                                .orElseThrow(() -> new MallException("INVALID_TASK_GROUP"));
                String raw = (String) row.get("request_snapshot");
                if (!"PENDING".equals(row.get("status"))
                        || !"LOCAL_PENDING".equals(row.get("dispatch_state"))
                        || !c.orderComponentNo().equals(row.get("order_component_no"))
                        || ((Number) row.get("unit_seq")).intValue() != cycle.sequence()
                        || raw == null
                        || !c.snapshot().equals(decode(raw, MallSnapshot.Component.class))
                        || !ai.neargo.commerce.core.spi.FactDigest.of(
                                        List.of(encode(decode(raw, MallSnapshot.Component.class))))
                                .equals(row.get("request_hash"))
                        || row.get("result_no") != null)
                    throw new MallException("INVALID_TASK_GROUP");
                if (!jdbc.queryForList(
                                "SELECT period_no FROM mch_subscription_period WHERE tenant_no=?"
                                        + " AND source_type='ORDER' AND source_ref_no=? FOR SHARE",
                                String.class,
                                store.tenantNo(),
                                no)
                        .isEmpty()) throw new MallException("INVALID_TASK_GROUP");
                expected.put(
                        no,
                        new PendingTask(
                                no,
                                c.orderComponentNo(),
                                cycle.sequence(),
                                ((Number) row.get("version")).longValue(),
                                (String) row.get("request_hash"),
                                cycle.cash(),
                                cycle.credit(),
                                !RefundPolicy.parse(line.sku().refundPolicySnapshot())
                                        .automaticUnused()));
            }
        }
        if (rows.size() != expected.size() || expected.isEmpty())
            throw new MallException("INVALID_TASK_GROUP");
        return List.copyOf(expected.values());
    }

    public void freezeTasks(State s) {
        guard(s.preview().store().tenantNo());
        for (var t : s.preview().pendingTasks())
            changed(
                    jdbc.update(
                            "UPDATE mall_fulfillment_task SET"
                                + " status='FROZEN',dispatch_state='REFUND_FROZEN',version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND order_no=? AND task_no=? AND version=?"
                                + " AND request_hash=? AND status='PENDING' AND"
                                + " dispatch_state='LOCAL_PENDING'",
                            s.preview().store().tenantNo(),
                            s.preview().orderNo(),
                            t.taskNo(),
                            t.version(),
                            t.requestHash()));
    }

    public void resolveTasks(State s, boolean success) {
        guard(s.preview().store().tenantNo());
        for (var t : s.preview().pendingTasks())
            changed(
                    jdbc.update(
                            "UPDATE mall_fulfillment_task SET"
                                + " status=?,dispatch_state=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND order_no=? AND task_no=? AND"
                                + " request_hash=? AND status='FROZEN' AND"
                                + " dispatch_state='REFUND_FROZEN'",
                            success ? "CANCELLED" : "PENDING",
                            success ? "REFUND_CANCELLED" : "LOCAL_PENDING",
                            s.preview().store().tenantNo(),
                            s.preview().orderNo(),
                            t.taskNo(),
                            t.requestHash()));
        if (success)
            for (String c :
                    s.preview().pendingTasks().stream()
                            .map(PendingTask::componentNo)
                            .distinct()
                            .toList())
                changed(
                        jdbc.update(
                                "UPDATE mall_order_component SET"
                                    + " fulfillment_status='CANCELLED',version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE tenant_no=? AND order_component_no=? AND"
                                    + " fulfillment_status='PENDING'",
                                s.preview().store().tenantNo(),
                                c));
    }

    public void create(State s) {
        var p = s.preview();
        guard(p.store().tenantNo());
        jdbc.update(
                "INSERT INTO"
                    + " mall_refund(tenant_no,created_at,updated_at,created_by,updated_by,version,deleted,refund_no,order_no,store_no,reason,policy_snapshot,requested_cash_minor,approved_cash_minor,restore_credit_minor,status,idempotency_key,reviewed_by,reviewed_at,command_snapshot,review_evidence)"
                    + " VALUES"
                    + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?,0,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.store().tenantNo(),
                p.buyerNo(),
                p.buyerNo(),
                p.refundNo(),
                p.orderNo(),
                p.store().storeNo(),
                s.reviewEvidence() == null ? "preview" : s.reviewEvidence(),
                encode(p),
                p.cashLimit(),
                s.approvedCash(),
                s.restoredCredit(),
                s.status(),
                p.refundNo(),
                s.reviewer(),
                s.reviewedAt(),
                encode(s.command()),
                s.reviewer() == null ? null : s.reviewEvidence());
        for (var source : p.sources())
            jdbc.update(
                    "INSERT INTO"
                        + " mall_refund_item(tenant_no,created_at,updated_at,version,deleted,refund_item_no,refund_no,order_component_no,period_no,quantity,cash_refund_minor,credit_restore_minor,rights_action,status,adjustment_snapshot)"
                        + " VALUES"
                        + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,?,1,0,0,'FREEZE_REMAINING','PREVIEW',?)",
                    p.store().tenantNo(),
                    UUID.randomUUID().toString(),
                    p.refundNo(),
                    source.componentNo(),
                    source.period().no(),
                    encode(source));
    }

    public void save(State s) {
        var p = s.preview();
        guard(p.store().tenantNo());
        var before = find(p.store().tenantNo(), p.refundNo()).orElseThrow();
        changed(
                jdbc.update(
                        "UPDATE mall_refund SET"
                            + " status=?,approved_cash_minor=?,restore_credit_minor=?,reviewed_by=?,reviewed_at=IF(?"
                            + " IS NULL,NULL,COALESCE(reviewed_at,?)),reason=IF(? IS"
                            + " NULL,?,reason),review_evidence=?,command_snapshot=?,verified_fact_snapshot=?,pay_refund_no=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                            + " WHERE tenant_no=? AND refund_no=?",
                        s.status(),
                        s.approvedCash(),
                        s.restoredCredit(),
                        s.reviewer(),
                        s.reviewer(),
                        s.reviewedAt(),
                        s.reviewer(),
                        s.reviewEvidence() == null ? "preview" : s.reviewEvidence(),
                        s.reviewer() == null ? null : s.reviewEvidence(),
                        encode(s.command()),
                        encode(s.fact()),
                        s.fact() == null ? null : s.fact().providerRefundNo(),
                        p.store().tenantNo(),
                        p.refundNo()));
        long cash = s.approvedCash(), credit = s.restoredCredit();
        for (var source : p.sources()) {
            long partCash = Math.min(cash, source.cashLimit()),
                    partCredit = Math.min(credit, source.creditLimit());
            cash -= partCash;
            credit -= partCredit;
            changed(
                    jdbc.update(
                            "UPDATE mall_refund_item SET"
                                + " status=?,cash_refund_minor=?,credit_restore_minor=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND refund_no=? AND period_no=?",
                            s.status(),
                            partCash,
                            partCredit,
                            p.store().tenantNo(),
                            p.refundNo(),
                            source.period().no()));
        }
        if ("SUCCEEDED".equals(s.status()) && !"SUCCEEDED".equals(before.status()))
            changed(
                    jdbc.update(
                            "UPDATE mall_order SET cash_refunded_minor=cash_refunded_minor+? WHERE"
                                    + " tenant_no=? AND order_no=? AND"
                                    + " cash_paid_minor-cash_refunded_minor>=?",
                            s.approvedCash(),
                            p.store().tenantNo(),
                            p.orderNo(),
                            s.approvedCash()));
    }

    public void freeze(State s, List<Period> before, long at) {
        var p = s.preview();
        var store = p.store();
        guard(store.tenantNo());
        changed(
                jdbc.update(
                        "UPDATE mch_subscription_account SET"
                            + " pending_change_order_no=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                            + " WHERE tenant_no=? AND store_no=? AND pending_change_order_no IS"
                            + " NULL",
                        p.refundNo(),
                        store.tenantNo(),
                        store.storeNo()));
        for (var source : p.sources()) {
            var period = source.period();
            long used;
            if (period.fixed())
                used = Math.max(0, Math.min(period.duration(), at - period.start()));
            else
                used =
                        p.history().values().stream()
                                .flatMap(List::stream)
                                .filter(
                                        x ->
                                                x.sourceNo().equals(period.no())
                                                        && x.startsAt().toEpochMilli() < at)
                                .mapToLong(
                                        x ->
                                                Math.max(
                                                        0,
                                                        Math.min(at, x.endsAt().toEpochMilli())
                                                                - x.startsAt().toEpochMilli()))
                                .reduce(0, Math::addExact);
            changed(
                    jdbc.update(
                            "UPDATE mch_subscription_period SET"
                                + " state='FROZEN',effective_end_at=GREATEST(original_start_at,IF(plan_no"
                                + " IS NULL,?,LEAST(original_end_at,?))),revoked_duration_ms=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND store_no=? AND period_no=? AND version=?"
                                + " AND state='NORMAL' AND converted_by_no IS NULL",
                            at,
                            at,
                            period.duration() - used,
                            store.tenantNo(),
                            store.storeNo(),
                            period.no(),
                            source.version()));
        }
    }

    private void clear(State s) {
        var p = s.preview();
        changed(
                jdbc.update(
                        "UPDATE mch_subscription_account SET"
                            + " pending_change_order_no=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                            + " WHERE tenant_no=? AND store_no=? AND pending_change_order_no=?",
                        p.store().tenantNo(),
                        p.store().storeNo(),
                        p.refundNo()));
    }

    public void restore(State s) {
        var p = s.preview();
        guard(p.store().tenantNo());
        for (var source : p.sources()) {
            var old = source.period();
            changed(
                    jdbc.update(
                            "UPDATE mch_subscription_period SET"
                                + " state=?,effective_end_at=?,revoked_duration_ms=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND store_no=? AND period_no=? AND"
                                + " state='FROZEN'",
                            old.lifecycle(),
                            old.end(),
                            old.revokedDuration(),
                            p.store().tenantNo(),
                            p.store().storeNo(),
                            old.no()));
        }
        clear(s);
    }

    public void complete(State s) {
        var p = s.preview();
        var store = p.store();
        guard(store.tenantNo());
        for (var source : p.sources())
            changed(
                    jdbc.update(
                            "UPDATE mch_subscription_period SET"
                                + " state='REFUNDED',version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND store_no=? AND period_no=? AND"
                                + " state='FROZEN'",
                            store.tenantNo(),
                            store.storeNo(),
                            source.period().no()));
        long left = s.restoredCredit();
        int seq = 0;
        for (var source : p.sources()) {
            long sourceLeft = Math.min(left, source.creditLimit());
            left -= sourceLeft;
            for (var use : source.creditSlices()) {
                long amount = Math.min(sourceLeft, use.amount());
                if (amount <= 0) continue;
                sourceLeft -= amount;
                Long original =
                        jdbc.queryForObject(
                                "SELECT amount_minor FROM mch_subscription_credit_ledger WHERE"
                                    + " tenant_no=? AND store_no=? AND ledger_no=? AND"
                                    + " credit_lot_no=? AND biz_ref_no=? AND event_type='CONSUME'"
                                    + " FOR SHARE",
                                Long.class,
                                store.tenantNo(),
                                store.storeNo(),
                                use.ledgerNo(),
                                use.lotNo(),
                                p.orderNo());
                long reversed =
                        jdbc
                                .queryForList(
                                        "SELECT amount_minor FROM mch_subscription_credit_ledger"
                                            + " WHERE tenant_no=? AND store_no=? AND"
                                            + " reversal_of_ledger_no=? AND event_type='RESTORE'"
                                            + " FOR SHARE",
                                        Long.class,
                                        store.tenantNo(),
                                        store.storeNo(),
                                        use.ledgerNo())
                                .stream()
                                .mapToLong(Long::longValue)
                                .reduce(0, Math::addExact);
                Long available = original == null ? null : Math.subtractExact(original, reversed);
                if (available == null || available < amount)
                    throw new MallException("CREDIT_PROVENANCE_MISSING");
                changed(
                        jdbc.update(
                                "UPDATE mch_subscription_credit_lot SET"
                                    + " remaining_minor=remaining_minor+?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE tenant_no=? AND store_no=? AND credit_lot_no=? AND"
                                    + " issued_minor-remaining_minor>=?",
                                amount,
                                store.tenantNo(),
                                store.storeNo(),
                                use.lotNo(),
                                amount));
                jdbc.update(
                        "INSERT INTO"
                            + " mch_subscription_credit_ledger(tenant_no,created_at,updated_at,version,deleted,ledger_no,store_no,currency,credit_lot_no,event_type,biz_ref_no,event_seq,amount_minor,balance_delta_minor,reserved_delta_minor,reversal_of_ledger_no,reason)"
                            + " VALUES"
                            + " (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,?,'RESTORE',?,?,?,?,0,?,'refund"
                            + " original provenance')",
                        store.tenantNo(),
                        UUID.randomUUID().toString(),
                        store.storeNo(),
                        p.currency(),
                        use.lotNo(),
                        p.refundNo(),
                        ++seq,
                        amount,
                        amount,
                        use.ledgerNo());
            }
            if (sourceLeft != 0) throw new MallException("CREDIT_PROVENANCE_MISSING");
        }
        if (left != 0) throw new MallException("CREDIT_PROVENANCE_MISSING");
        if (s.restoredCredit() > 0)
            changed(
                    jdbc.update(
                            "UPDATE mch_subscription_credit_account SET"
                                + " balance_minor=balance_minor+?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                                + " WHERE tenant_no=? AND store_no=? AND currency=?",
                            s.restoredCredit(),
                            store.tenantNo(),
                            store.storeNo(),
                            p.currency()));
        clear(s);
    }

    public boolean fullyRefunded(MallSnapshot.Order order) {
        var store = order.quote().store();
        guard(store.tenantNo());
        long cashLeft =
                jdbc.queryForObject(
                        "SELECT cash_paid_minor-cash_refunded_minor FROM mall_order WHERE"
                                + " tenant_no=? AND order_no=? FOR SHARE",
                        Long.class,
                        store.tenantNo(),
                        order.orderNo());
        long restored =
                jdbc
                        .queryForList(
                                "SELECT restore_credit_minor FROM mall_refund WHERE tenant_no=? AND"
                                        + " order_no=? AND status='SUCCEEDED' FOR SHARE",
                                Long.class,
                                store.tenantNo(),
                                order.orderNo())
                        .stream()
                        .mapToLong(Long::longValue)
                        .reduce(0, Math::addExact);
        boolean remaining =
                !jdbc.queryForList(
                                "SELECT p.period_no FROM mch_subscription_period p JOIN"
                                    + " mall_fulfillment_task t ON t.tenant_no=p.tenant_no AND"
                                    + " t.task_no=p.source_ref_no AND t.task_type='DIGITAL' JOIN"
                                    + " mall_order_component c ON c.tenant_no=t.tenant_no AND"
                                    + " c.order_component_no=t.order_component_no JOIN"
                                    + " mall_order_item i ON i.tenant_no=c.tenant_no AND"
                                    + " i.order_item_no=c.order_item_no WHERE p.tenant_no=? AND"
                                    + " p.store_no=? AND i.order_no=? AND p.state NOT IN"
                                    + " ('REFUNDED','CONVERTED') LIMIT 1 FOR SHARE",
                                String.class,
                                store.tenantNo(),
                                store.storeNo(),
                                order.orderNo())
                        .isEmpty();
        long cancelledCredit =
                jdbc
                        .queryForList(
                                "SELECT policy_snapshot FROM mall_refund WHERE tenant_no=? AND"
                                        + " order_no=? AND status='SUCCEEDED' FOR SHARE",
                                String.class,
                                store.tenantNo(),
                                order.orderNo())
                        .stream()
                        .map(raw -> decode(raw, Preview.class))
                        .flatMap(p -> p.pendingTasks().stream())
                        .mapToLong(PendingTask::credit)
                        .reduce(0, Math::addExact);
        return cashLeft == 0
                && Math.addExact(restored, cancelledCredit) == order.quote().credit()
                && !remaining;
    }

    public boolean hasReservedHold(MallSnapshot.Order o) {
        var store = o.quote().store();
        guard(store.tenantNo());
        return !jdbc.queryForList(
                        "SELECT reservation_no FROM mch_subscription_reservation WHERE tenant_no=?"
                                + " AND store_no=? AND order_no=? AND status='RESERVED' FOR UPDATE",
                        String.class,
                        store.tenantNo(),
                        store.storeNo(),
                        o.orderNo())
                .isEmpty();
    }
}
