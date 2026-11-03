package ai.neargo.commerce.persistence;

import static ai.neargo.commerce.core.catalog.CatalogSupport.json;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.CommerceIdGenerator;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.AccountSnapshot;
import ai.neargo.commerce.core.subscription.*;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import javax.sql.DataSource;

public final class JdbcSubscriptionRepository implements SubscriptionRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final JdbcSubscriptionAccountRepository accounts;
    private final CommerceIdGenerator ids;

    public JdbcSubscriptionRepository(DataSource ds, CommerceIdGenerator ids) {
        this.ds = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.ids = ids;
        accounts = new JdbcSubscriptionAccountRepository(ds);
    }

    public void requireOuterTransaction(StoreRef s) {
        JdbcWriteGuard.require(ds, s.tenantNo());
    }

    public AccountSnapshot lock(StoreRef s) {
        return accounts.lockSubscriptionAccount(s);
    }

    public void ensureAccount(StoreSnapshot snapshot, String actor) {
        var s = snapshot.store();
        requireOuterTransaction(s);
        jdbc.update(
                """
INSERT INTO mch_subscription_account
(tenant_no,store_no,entity_no,business_type_code,entitlement_version,created_by,updated_by,created_at,updated_at,version,deleted)
VALUES (?,?,?,?,0,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0) ON DUPLICATE KEY UPDATE id=id
""",
                s.tenantNo(),
                s.storeNo(),
                snapshot.merchantNo(),
                snapshot.businessTypeCode(),
                actor,
                actor);
    }

    public List<Period> periods(StoreRef s) {
        requireOuterTransaction(s);
        var ends = new HashMap<String, Map<String, String>>();
        jdbc.query(
                """
                SELECT p.period_no,e.ec,e.bc FROM mch_subscription_period p
                JOIN JSON_TABLE(p.source_policy_snapshot,'$.ends[*]' COLUMNS(
                    ec VARCHAR(64) PATH '$.code',bc VARCHAR(64) PATH '$.end')) e ON TRUE
                WHERE p.tenant_no=? AND p.store_no=? AND p.state='NORMAL' FOR SHARE OF p
                """,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        r ->
                                ends.computeIfAbsent(r.getString(1), k -> new TreeMap<>())
                                        .put(r.getString(2), r.getString(3)),
                s.tenantNo(),
                s.storeNo());
        return jdbc.query(
                """
SELECT p.*,p.source_policy_snapshot->>'$.policy' policy,p.source_policy_snapshot->>'$.gift' gift FROM mch_subscription_period p WHERE tenant_no=? AND store_no=? AND state='NORMAL' ORDER BY delivery_seq,id FOR SHARE
""",
                (r, i) ->
                        new Period(
                                r.getString("period_no"),
                                r.getString("source_ref_no"),
                                r.getString("source_type"),
                                r.getString("plan_no"),
                                r.getString("plan_version_no"),
                                r.getString("tier_code"),
                                r.getLong("delivered_at"),
                                r.getLong("original_start_at"),
                                r.getObject("granted_duration_ms", Long.class),
                                ends.getOrDefault(r.getString("period_no"), Map.of()),
                                r.getLong("cash_basis_minor"),
                                r.getLong("credit_basis_minor"),
                                r.getString("currency"),
                                r.getString("policy"),
                                Boolean.parseBoolean(r.getString("gift"))),
                s.tenantNo(),
                s.storeNo());
    }

    public List<AccessSegment> segments(StoreRef s, String code) {
        requireOuterTransaction(s);
        return jdbc.query(
                """
SELECT period_no,start_at,end_at FROM mch_store_end_grant WHERE tenant_no=? AND store_no=? AND entitlement_code=?
AND state='NORMAL' ORDER BY start_at,period_no FOR SHARE
""",
                (r, i) ->
                        new AccessSegment(
                                r.getString(1),
                                code,
                                Instant.ofEpochMilli(r.getLong(2)),
                                r.getObject(3) == null ? null : Instant.ofEpochMilli(r.getLong(3))),
                s.tenantNo(),
                s.storeNo(),
                code);
    }

    public long lastChange(StoreRef s) {
        requireOuterTransaction(s);
        var cuts =
                jdbc.queryForList(
                        "SELECT effective_at FROM mch_end_schedule_event WHERE tenant_no=? AND"
                            + " store_no=? ORDER BY effective_at DESC LIMIT 1 FOR SHARE",
                        Long.class,
                        s.tenantNo(),
                        s.storeNo());
        var deliveries =
                jdbc.queryForList(
                        "SELECT delivered_at FROM mch_subscription_period WHERE tenant_no=? AND"
                            + " store_no=? ORDER BY delivered_at DESC LIMIT 1 FOR SHARE",
                        Long.class,
                        s.tenantNo(),
                        s.storeNo());
        return Math.max(
                cuts.isEmpty() ? Long.MIN_VALUE : cuts.getFirst(),
                deliveries.isEmpty() ? Long.MIN_VALUE : deliveries.getFirst());
    }

    public void insert(AccountSnapshot a, Period p, long seq, String actor) {
        var s = a.store();
        requireOuterTransaction(s);
        String code = p.fixed() ? null : p.ends().keySet().iterator().next();
        String stream =
                p.duration() == null ? "FREE" : p.fixed() ? "BASE_PLAN" : "END_ACCESS:" + code;
        String bindings =
                p.ends().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                e ->
                                        "{\"code\":"
                                                + json(e.getKey())
                                                + ",\"end\":"
                                                + json(e.getValue())
                                                + "}")
                        .collect(
                                Collectors.joining(
                                        ",",
                                        "{\"policy\":"
                                                + json(p.policyVersion())
                                                + ",\"gift\":"
                                                + p.gift()
                                                + ",\"ends\":[",
                                        "]}"));
        jdbc.update(
                """
INSERT INTO mch_subscription_period
(tenant_no,store_no,entity_no,business_type_code,period_no,source_ref_no,source_type,stream_key,fulfillment_task_no,
plan_no,plan_version_no,standalone_entitlement_code,tier_code,delivered_at,delivery_seq,granted_duration_ms,revoked_duration_ms,
original_start_at,original_end_at,effective_start_at,effective_end_at,eligible_basis_minor,cash_basis_minor,credit_basis_minor,
currency,state,source_policy_snapshot,created_by,updated_by,created_at,updated_at,version,deleted)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,'NORMAL',?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
""",
                s.tenantNo(),
                s.storeNo(),
                a.entityNo(),
                a.businessTypeCode(),
                p.no(),
                p.origin(),
                p.sourceType(),
                stream,
                "ORDER".equals(p.sourceType()) ? p.origin() : null,
                p.planNo(),
                p.planVersion(),
                code,
                p.tier(),
                p.delivered(),
                seq,
                p.duration(),
                p.start(),
                p.end(),
                p.start(),
                p.end(),
                p.basis(),
                p.cash(),
                p.credit(),
                p.currency(),
                bindings,
                actor,
                actor);
    }

    private static String snapshot(List<AccessSegment> rows) {
        return rows.stream()
                .map(
                        s ->
                                "{\"period\":"
                                        + json(s.sourceNo())
                                        + ",\"from\":"
                                        + s.startsAt().toEpochMilli()
                                        + ",\"to\":"
                                        + (s.endsAt() == null ? "null" : s.endsAt().toEpochMilli())
                                        + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String durationCheck(
            String code, List<Period> periods, List<AccessSegment> after, long at) {
        var checks = new ArrayList<String>();
        for (var p : periods) {
            if (p.duration() == null || !p.ends().containsKey(code)) continue;
            long used = 0, total = 0;
            for (var s : after)
                if (s.sourceNo().equals(p.no())) {
                    long start = s.startsAt().toEpochMilli(), end = s.endsAt().toEpochMilli();
                    total = Math.addExact(total, Math.subtractExact(end, start));
                    if (at > start)
                        used = Math.addExact(used, Math.subtractExact(Math.min(end, at), start));
                }
            if (total != p.duration())
                throw new IllegalStateException("source duration not conserved");
            checks.add(
                    "{\"period\":"
                            + json(p.no())
                            + ",\"granted\":"
                            + total
                            + ",\"used\":"
                            + used
                            + ",\"remaining\":"
                            + (total - used)
                            + ",\"revoked\":0}");
        }
        return "[" + String.join(",", checks) + "]";
    }

    public void revise(
            StoreRef s,
            String code,
            String op,
            long at,
            long rev,
            List<AccessSegment> before,
            List<AccessSegment> after,
            List<Period> periods,
            String actor) {
        requireOuterTransaction(s);
        String event = ids.nextBusinessNo("schedule-event");
        jdbc.update(
                """
INSERT INTO mch_end_schedule_event
(tenant_no,store_no,entitlement_code,entitlement_version,operation_ref_no,schedule_event_no,reason_type,effective_at,
before_snapshot,after_snapshot,duration_check_snapshot,stacking_policy_version,created_by,updated_by,created_at,updated_at,version,deleted)
VALUES (?,?,?,?,?,?,'SCHEDULE_REBUILD',?,?,?,?,'FIFO_V1',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
""",
                s.tenantNo(),
                s.storeNo(),
                code,
                rev,
                op,
                event,
                at,
                snapshot(before),
                snapshot(after),
                durationCheck(code, periods, after, at),
                actor,
                actor);
        var retained = new HashSet<>(after);
        for (var old : before)
            if (!retained.contains(old)) {
                jdbc.update(
                        """
UPDATE mch_store_end_grant SET state='SUPERSEDED',updated_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?
WHERE tenant_no=? AND store_no=? AND period_no=? AND entitlement_code=? AND start_at=? AND state='NORMAL'
""",
                        actor,
                        s.tenantNo(),
                        s.storeNo(),
                        old.sourceNo(),
                        code,
                        old.startsAt().toEpochMilli());
            }
        var prior = new HashSet<>(before);
        var parents = periods.stream().collect(Collectors.toMap(Period::no, p -> p));
        int seq = 0;
        for (var segment : after)
            if (!prior.contains(segment)) {
                var p = parents.get(segment.sourceNo());
                jdbc.update(
                        """
INSERT INTO mch_store_end_grant
(tenant_no,store_no,period_no,entitlement_code,end_code,grant_no,schedule_event_no,schedule_revision,segment_seq,start_at,
end_at,state,reason,created_by,updated_by,created_at,updated_at,version,deleted)
VALUES (?,?,?,?,?,?,?,?,?,?,?,'NORMAL',?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
""",
                        s.tenantNo(),
                        s.storeNo(),
                        p.no(),
                        code,
                        p.ends().get(code),
                        ids.nextBusinessNo("grant"),
                        event,
                        rev,
                        ++seq,
                        segment.startsAt().toEpochMilli(),
                        segment.endsAt() == null ? null : segment.endsAt().toEpochMilli(),
                        op,
                        actor,
                        actor);
            }
        for (var p : periods)
            if (!p.fixed() && p.ends().containsKey(code)) {
                var slices = after.stream().filter(g -> g.sourceNo().equals(p.no())).toList();
                long start = slices.getFirst().startsAt().toEpochMilli(),
                        end = slices.getLast().endsAt().toEpochMilli();
                boolean first = before.stream().noneMatch(g -> g.sourceNo().equals(p.no()));
                jdbc.update(
                        """
UPDATE mch_subscription_period SET original_start_at=IF(?, ?, original_start_at),original_end_at=IF(?, ?, original_end_at),
effective_start_at=?,effective_end_at=?,updated_at=UTC_TIMESTAMP(3),updated_by=?,version=version+1
WHERE tenant_no=? AND store_no=? AND period_no=? AND (effective_start_at<>? OR effective_end_at<>?)
""",
                        first,
                        start,
                        first,
                        end,
                        start,
                        end,
                        actor,
                        s.tenantNo(),
                        s.storeNo(),
                        p.no(),
                        start,
                        end);
            }
    }

    public void updateVersion(AccountSnapshot a, long rev, String actor) {
        var s = a.store();
        requireOuterTransaction(s);
        int changed =
                jdbc.update(
                        "UPDATE mch_subscription_account SET"
                            + " entitlement_version=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                            + " WHERE tenant_no=? AND store_no=? AND entitlement_version=?",
                        rev,
                        actor,
                        s.tenantNo(),
                        s.storeNo(),
                        a.entitlementVersion());
        if (changed != 1) throw new IllegalStateException("stale subscription account");
    }
}
