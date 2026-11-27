package ai.neargo.commerce.persistence.platform;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.port.CommerceIdGenerator;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.access.EndAccessService;
import ai.neargo.commerce.core.foundation.FoundationRepository.GrantState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.HashSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcPlatformEndGrantStore {
    public record Change(String store, String code, String end, String client, String operation,
            long effectiveAt, long revision, String actor, String reason, Set<String> withdrawn, List<AccessSegment> segments) {}
    public record Task(String no, Change change) {}

    private final JdbcTemplate platform;
    private final TransactionTemplate write;
    private final CommerceIdGenerator ids;

    public JdbcPlatformEndGrantStore(DataSource dataSource, CommerceIdGenerator ids) {
        this.ids = ids;
        platform = new JdbcTemplate(dataSource);
        write = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void applyAll(StoreRef store, List<Task> tasks) {
        write.executeWithoutResult(ignored -> {
            platform.queryForObject("""
                    SELECT id FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0 FOR UPDATE
                    """, Long.class, store.tenantNo(),store.storeNo());
            for (var task : tasks) apply(store, task);
        });
    }

    public Map<String, GrantState> grantStates(StoreRef store) {
        var result = new TreeMap<String, GrantState>();
        platform.query("""
                SELECT entitlement_code,end_code,MAX(state='REVOKED') FROM mch_store_end_grant
                WHERE tenant_no=? AND store_no=? AND deleted=0 GROUP BY entitlement_code,end_code
                """, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                    var old = result.put(r.getString(1), new GrantState(r.getString(2), r.getBoolean(3)));
                    if (old != null) throw new IllegalStateException("conflicting platform grant binding");
                }, store.tenantNo(),store.storeNo());
        return result;
    }

    public List<AccessSegment> activated(StoreRef store, String code) {
        return platform.query("""
                SELECT period_no,start_at,end_at FROM mch_store_end_grant WHERE tenant_no=? AND store_no=?
                AND entitlement_code=? AND state IN ('ACTIVE','SCHEDULED','ENDED') AND deleted=0 ORDER BY start_at,id
                """, (r,i) -> new AccessSegment(r.getString(1), code, Instant.ofEpochMilli(r.getLong(2)),
                        r.getObject(3) == null ? null : Instant.ofEpochMilli(r.getLong(3))),
                store.tenantNo(), store.storeNo(), code);
    }

    private void apply(StoreRef store, Task task) {
        var change = task.change();
        // Replaying after a lost acknowledgement must leave even an empty replacement unchanged.
        Long latest = platform.queryForObject("""
                SELECT MAX(entitlement_version) FROM mch_store_end_grant
                WHERE tenant_no=? AND store_no=? AND entitlement_code=?
                """, Long.class, store.tenantNo(), store.storeNo(), change.code());
        if (latest != null && latest >= change.revision()) return;
        record Current(long id, AccessSegment segment, String end, String client) {}
        var current = platform.query("""
                SELECT id,period_no,start_at,end_at,end_code,client_id FROM mch_store_end_grant
                WHERE tenant_no=? AND store_no=? AND entitlement_code=? AND state<>'REVOKED' AND deleted=0
                """, (r,i) -> new Current(r.getLong(1), new AccessSegment(r.getString(2),change.code(),
                        Instant.ofEpochMilli(r.getLong(3)),r.getObject(4) == null ? null : Instant.ofEpochMilli(r.getLong(4))),
                        r.getString(5),r.getString(6)),store.tenantNo(),store.storeNo(),change.code());
        var missing = new HashSet<>(change.segments());
        for (var old : current) {
            if (change.end().equals(old.end()) && java.util.Objects.equals(change.client(),old.client())
                    && missing.remove(old.segment())) {
                platform.update("UPDATE mch_store_end_grant SET entitlement_version=?,version=version+1 WHERE id=?",
                        change.revision(),old.id());
            } else {
                boolean withdrawn = change.withdrawn().contains(old.segment().sourceNo());
                platform.update("""
                        UPDATE mch_store_end_grant SET state='REVOKED',revoked_at=?,entitlement_version=?,
                            deleted=?,reason_type=?,reason=?,updated_by=?,version=version+1 WHERE id=?
                        """, change.effectiveAt(),change.revision(),!withdrawn, change.reason(),change.operation(),
                        change.actor(),old.id());
            }
        }
        int seq = 0;
        for (var segment : change.segments()) {
            if (!missing.contains(segment)) continue;
            long start = segment.startsAt().toEpochMilli();
            Long until = segment.endsAt() == null ? null : segment.endsAt().toEpochMilli();
            String state = start > change.effectiveAt() ? "SCHEDULED"
                    : until != null && until <= change.effectiveAt() ? "ENDED" : "ACTIVE";
            platform.update("""
                    INSERT INTO mch_store_end_grant(tenant_no,store_no,end_code,entitlement_code,client_id,
                      period_no,grant_no,entitlement_version,schedule_revision,segment_seq,schedule_event_no,
                      operation_ref_no,reason_type,stacking_policy_version,start_at,end_at,state,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'FIFO_V1',?,?,?,?,?)
                    """, store.tenantNo(),store.storeNo(),change.end(),change.code(),change.client(),
                    segment.sourceNo(),ids.nextBusinessNo("grant"),change.revision(),change.revision(),
                    ++seq,task.no(),change.operation(),change.reason(),start,until,state,change.actor(),change.actor());
        }
    }

    public static EndAccessService.State access(JdbcTemplate jdbc, StoreRef store, String end, String code, String client) {
        record Owner(String entity, String type) {}
        var owner = jdbc.queryForObject("""
                SELECT entity_no,business_type_code FROM mch_store
                WHERE tenant_no=? AND store_no=? AND deleted=0 FOR SHARE
                """, (r, i) -> new Owner(r.getString(1), r.getString(2)), store.tenantNo(), store.storeNo());
        record Grant(String end, String client, String period, long start, Long until, String state, long revision) {}
        var grants = jdbc.query("""
                SELECT end_code,client_id,period_no,start_at,end_at,state,entitlement_version
                FROM mch_store_end_grant WHERE tenant_no=? AND store_no=? AND entitlement_code=? AND deleted=0
                ORDER BY start_at,id FOR SHARE
                """, (r, i) -> new Grant(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),
                        r.getObject(5, Long.class),r.getString(6),r.getLong(7)),
                store.tenantNo(), store.storeNo(), code);
        long revision = 0;
        boolean revoked = false;
        var segments = new ArrayList<AccessSegment>();
        for (var grant : grants) {
            revision = Math.max(revision, grant.revision());
            if ("REVOKED".equals(grant.state())) {
                revoked = true;
                continue;
            }
            if (!end.equals(grant.end()) || !client.equals(grant.client()))
                throw new AccessDenied(AccessStatus.FORBIDDEN);
            switch (grant.state()) {
                case "ACTIVE", "SCHEDULED", "ENDED" -> segments.add(new AccessSegment(grant.period(),
                        code, Instant.ofEpochMilli(grant.start()), grant.until() == null
                                ? null : Instant.ofEpochMilli(grant.until())));
                default -> throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
            }
        }
        return new EndAccessService.State(owner.entity(), owner.type(), revision, segments, revoked);
    }
}
