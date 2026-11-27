package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.port.CommerceIdGenerator;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.Period;
import java.util.List;
import java.util.Map;
import ai.neargo.commerce.persistence.platform.JdbcPlatformEndGrantStore;
import ai.neargo.commerce.persistence.platform.JdbcPlatformEndGrantStore.Task;
import ai.neargo.commerce.persistence.platform.JdbcPlatformEndGrantStore.Change;

import ai.neargo.commerce.core.foundation.FoundationRepository.GrantState;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The mall keeps the purchased schedule. Only a committed activation changes platform access. */
public final class JdbcEndActivation {
    private static final System.Logger LOG = System.getLogger(JdbcEndActivation.class.getName());
    private final JdbcTemplate mall;
    private final JdbcPlatformEndGrantStore platform;
    private final TransactionTemplate mallTx;
    private final CommerceIdGenerator ids;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcEndActivation(DataSource mallSource, DataSource platformSource, CommerceIdGenerator ids) {
        mall = new JdbcTemplate(mallSource);
        platform = new JdbcPlatformEndGrantStore(platformSource, ids);
        mallTx = newTransaction(mallSource);
        this.ids = ids;
    }

    private static TransactionTemplate newTransaction(DataSource source) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    List<AccessSegment> purchased(StoreRef store, String code) {
        var schedules = mall.queryForList("""
                SELECT request_snapshot FROM mall_fulfillment_task WHERE tenant_no=?
                AND task_type='END_ACTIVATION' AND request_snapshot->>'$.store'=?
                AND request_snapshot->>'$.code'=? ORDER BY id DESC LIMIT 1 FOR SHARE
                """, String.class, store.tenantNo(), store.storeNo(), code);
        return schedules.isEmpty() ? List.of() : read(schedules.getFirst()).segments();
    }

    Map<String, GrantState> grantStates(StoreRef store) { return platform.grantStates(store); }

    List<AccessSegment> activated(StoreRef store, String code) { return platform.activated(store, code); }

    long lastChange(StoreRef store) {
        Long at = mall.queryForObject("""
                SELECT MAX(CAST(request_snapshot->>'$.effectiveAt' AS SIGNED)) FROM mall_fulfillment_task
                WHERE tenant_no=? AND task_type='END_ACTIVATION' AND request_snapshot->>'$.store'=?
                """, Long.class, store.tenantNo(), store.storeNo());
        return at == null ? Long.MIN_VALUE : at;
    }

    void enqueue(StoreRef store, String code, String op, long at, long revision,
            List<AccessSegment> after, List<Period> periods, String actor) {
        var sources = periods.stream().filter(p -> p.ends().containsKey(code)).toList();
        var end = sources.stream().map(p -> p.ends().get(code)).distinct().toList();
        if (end.size() != 1) throw new IllegalStateException("conflicting source end binding");
        var clients = mall.queryForList("""
                SELECT e.client_id FROM mall_end_entitlement e JOIN mch_subscription_account a
                ON e.tenant_no=a.tenant_no AND a.store_no=? WHERE e.tenant_no=? AND e.end_code=?
                AND e.entitlement_code=? AND e.scope='STORE' AND e.enabled=1 AND e.deleted=0
                AND e.release_status IN ('GA','VERIFIED')
                AND (JSON_LENGTH(e.allowed_business_types)=0
                     OR JSON_CONTAINS(e.allowed_business_types,JSON_QUOTE(a.business_type_code)))
                """, String.class, store.storeNo(), store.tenantNo(), end.getFirst(), code);
        String client = clients.isEmpty() ? null : clients.getFirst();
        String reason = sources.stream().anyMatch(p -> "FROZEN".equals(p.lifecycle()) || "REFUNDED".equals(p.lifecycle()))
                ? "REFUND" : sources.stream().anyMatch(p -> !"NORMAL".equals(p.lifecycle()))
                ? "OPS_ADJUST" : sources.stream().allMatch(Period::gift) ? "GIFT" : "PURCHASE";
        var withdrawn = sources.stream().filter(p -> !"NORMAL".equals(p.lifecycle())).map(Period::no)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var change = new Change(store.storeNo(),code,end.getFirst(),client,op,at,revision,actor,reason,withdrawn,after);
        String no = ids.nextBusinessNo("activation");
        mall.update("""
                INSERT INTO mall_fulfillment_task(tenant_no,task_no,unit_seq,task_type,status,attempt_count,
                    next_retry_at,request_snapshot,created_at,updated_at,version,deleted)
                VALUES (?,?,1,'END_ACTIVATION','PENDING',0,0,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
                """, store.tenantNo(), no, json.writeValueAsString(change));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCommit() { applyThrough(store, revision); }
        });
    }

    private Change read(String serialized) { return json.readValue(serialized, Change.class); }

    /** One earliest pending revision per store per pass, across all tenants. */
    public int retryPendingAll(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("invalid activation page");
        record Due(StoreRef store, long revision) {}
        var due = mall.query("""
                SELECT tenant_no,store_no,revision FROM (
                    SELECT tenant_no,request_snapshot->>'$.store' store_no,
                        CAST(request_snapshot->>'$.revision' AS UNSIGNED) revision,next_retry_at,id,
                        ROW_NUMBER() OVER (PARTITION BY tenant_no,request_snapshot->>'$.store' ORDER BY id) ordinal
                    FROM mall_fulfillment_task WHERE task_type='END_ACTIVATION' AND status='PENDING'
                ) head WHERE ordinal=1 AND next_retry_at<=? ORDER BY next_retry_at,id LIMIT ?
                """, (r,i) -> new Due(new StoreRef(r.getString(1), r.getString(2)), r.getLong(3)),
                System.currentTimeMillis(), limit);
        int failed = 0;
        for (var item : due) if (!applyThrough(item.store(), item.revision())) failed++;
        return failed;
    }

    private boolean applyThrough(StoreRef store, long through) {
        try {
            applyPurchased(store, through);
            return true;
        } catch (RuntimeException failure) {
            // Purchase has committed. Leave its durable intent retryable even when platform is down.
            LOG.log(System.Logger.Level.WARNING, "End activation pending for " + store, failure);
            mallTx.executeWithoutResult(ignored -> mall.update("""
                    UPDATE mall_fulfillment_task SET attempt_count=attempt_count+1,
                        last_error_code='PLATFORM_ACTIVATION_FAILED',next_retry_at=?,version=version+1
                    WHERE tenant_no=? AND task_type='END_ACTIVATION' AND status='PENDING'
                    AND request_snapshot->>'$.store'=? AND CAST(request_snapshot->>'$.revision' AS UNSIGNED)<=?
                    """, System.currentTimeMillis() + 30000, store.tenantNo(), store.storeNo(), through));
            return false;
        }
    }

    private void applyPurchased(StoreRef store, long through) {
        mallTx.executeWithoutResult(status -> {
            // Serialize claims for a store, including recovery after platform commit/ack loss.
            mall.queryForObject("""
                    SELECT id FROM mch_subscription_account WHERE tenant_no=? AND store_no=? FOR UPDATE
                    """, Long.class, store.tenantNo(), store.storeNo());
            var tasks = mall.query("""
                    SELECT task_no,request_snapshot FROM mall_fulfillment_task WHERE tenant_no=?
                    AND task_type='END_ACTIVATION' AND status='PENDING' AND request_snapshot->>'$.store'=?
                    AND CAST(request_snapshot->>'$.revision' AS UNSIGNED)<=?
                    ORDER BY id FOR UPDATE
                    """, (r,i) -> new Task(r.getString(1), read(r.getString(2))),
                    store.tenantNo(), store.storeNo(), through);
            if (tasks.isEmpty()) return;
            platform.applyAll(store, tasks);
            for (var task : tasks) {
                mall.update("""
                        UPDATE mall_fulfillment_task SET status='DONE',attempt_count=attempt_count+1,
                            last_error_code=NULL,version=version+1 WHERE tenant_no=? AND task_no=?
                        """, store.tenantNo(), task.no());
            }
        });
    }

}
