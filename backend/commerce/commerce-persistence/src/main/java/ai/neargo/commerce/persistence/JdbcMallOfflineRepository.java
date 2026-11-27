package ai.neargo.commerce.persistence;

import ai.neargo.commerce.core.offline.OfflineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;

/** Mall database representation of offline declarations, processing and private evidence. */
public final class JdbcMallOfflineRepository implements OfflineRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcMallOfflineRepository(DataSource ds) {
        this.ds = ds;
        jdbc = new JdbcTemplate(ds);
    }

    private static LocalDateTime time(long at) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneOffset.UTC);
    }

    public void reserve(Evidence evidence) {
        var scope = evidence.scope();
        JdbcWriteGuard.require(ds, scope.store().tenantNo());
        jdbc.update("""
                INSERT INTO mall_offline_evidence
                (tenant_no,asset_key,order_no,submitter_no,purpose,scope_snapshot,created_at,updated_at,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, scope.store().tenantNo(), evidence.assetKey(), scope.orderNo(), scope.submitterNo(), scope.purpose(),
                json.writeValueAsString(evidence), time(evidence.createdAt()), time(evidence.createdAt()), scope.submitterNo(), scope.submitterNo());
    }

    public Optional<Evidence> evidence(String tenant, String key) {
        JdbcWriteGuard.require(ds, tenant);
        return jdbc.query("SELECT scope_snapshot FROM mall_offline_evidence WHERE tenant_no=? AND asset_key=? FOR UPDATE",
                (r,n) -> json.readValue(r.getString(1), Evidence.class), tenant, key).stream().findFirst();
    }

    public List<String> retained(String tenant) {
        JdbcWriteGuard.require(ds, tenant);
        return jdbc.queryForList("SELECT asset_key FROM mall_offline_evidence WHERE tenant_no=?", String.class, tenant);
    }

    private <T> List<T> records(Class<T> type, String tenant, String condition, Object... parameters) {
        JdbcWriteGuard.require(ds, tenant);
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(type == Claim.class ? "CLAIM" : "ACTION");
        Collections.addAll(args, parameters);
        return jdbc.query("SELECT action_snapshot FROM mall_offline_payment WHERE tenant_no=? AND record_type=? AND " + condition,
                (r,n) -> json.readValue(r.getString(1), type), args.toArray());
    }

    public Optional<Claim> claim(String tenant, String order, String no) {
        return records(Claim.class, tenant, "order_no=? AND action_no=? FOR UPDATE", order, no).stream().findFirst();
    }

    public List<Claim> claims(String tenant, String order) {
        return records(Claim.class, tenant, "order_no=? ORDER BY action_no", order);
    }

    public List<Claim> submittedClaims(String tenant, String afterOrder, String afterClaim, int limit) {
        return records(Claim.class, tenant, "status='SUBMITTED' AND (order_no,action_no)>(?,?) ORDER BY order_no,action_no LIMIT ?",
                Objects.toString(afterOrder,""), Objects.toString(afterClaim,""), limit);
    }

    public Optional<Action> action(String tenant, String order, String no) {
        return records(Action.class, tenant, "order_no=? AND action_no=? FOR UPDATE", order, no).stream().findFirst();
    }

    public List<Action> actions(String tenant, String order) {
        return records(Action.class, tenant, "order_no=? ORDER BY action_no", order);
    }

    public List<Action> pending(String tenant, String order) {
        return records(Action.class, tenant, "order_no=? AND status='ACCEPTED' ORDER BY action_no FOR UPDATE", order);
    }

    public List<String> pendingOrders(String tenant, String after, int limit) {
        JdbcWriteGuard.require(ds, tenant);
        return jdbc.queryForList("""
                SELECT DISTINCT order_no FROM mall_offline_payment WHERE tenant_no=? AND record_type='ACTION'
                AND status='ACCEPTED' AND order_no>? ORDER BY order_no LIMIT ?
                """, String.class, tenant, Objects.toString(after,""), limit);
    }

    public void saveClaim(Claim claim) {
        var cmd = claim.command();
        var tenant = cmd.order().store().tenantNo();
        JdbcWriteGuard.require(ds, tenant);
        String writer = Objects.requireNonNullElse(claim.reviewer(), claim.submitterNo());
        jdbc.update("""
                INSERT INTO mall_offline_payment
                (tenant_no,order_no,action_no,record_type,claim_no,currency,amount_minor,transferred_at,operator_no,status,
                 review_note,action_snapshot,created_at,updated_at,created_by,updated_by)
                VALUES (?,?,?,'CLAIM',?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?,?) AS incoming
                ON DUPLICATE KEY UPDATE status=incoming.status,review_note=incoming.review_note,
                action_snapshot=incoming.action_snapshot,updated_by=incoming.updated_by,updated_at=UTC_TIMESTAMP(3),
                version=mall_offline_payment.version+1
                """, tenant,cmd.order().orderNo(),cmd.claimNo(),cmd.claimNo(),cmd.declaredAmount().currency(),
                cmd.declaredAmount().minor(),cmd.declaredPaidAt().toEpochMilli(),claim.submitterNo(),claim.status(),claim.reason(),
                json.writeValueAsString(claim),time(claim.submittedAt()),claim.submitterNo(),writer);
        bindEvidence(tenant, cmd.order().orderNo(), cmd.claimNo(), cmd.evidenceKeys());
    }

    public void saveAction(Action action) {
        saveAction(action, null);
    }

    @Override
    public void saveAction(Action action, String approvalNo) {
        var tenant = action.order().store().tenantNo();
        var review = action.review();
        JdbcWriteGuard.require(ds, tenant);
        jdbc.update("""
                INSERT INTO mall_offline_payment
                (tenant_no,order_no,action_no,record_type,claim_no,kind,currency,amount_minor,transferred_at,operator_no,status,
                 review_note,confirmed_at,payment_no,failure_code,action_snapshot,review_no,created_at,updated_at,created_by,updated_by)
                VALUES (?,?,?,'ACTION',?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?,?) AS incoming
                ON DUPLICATE KEY UPDATE status=incoming.status,confirmed_at=incoming.confirmed_at,payment_no=incoming.payment_no,
                failure_code=incoming.failure_code,action_snapshot=incoming.action_snapshot,
                updated_at=UTC_TIMESTAMP(3),updated_by=incoming.updated_by,version=mall_offline_payment.version+1
                """, tenant,action.order().orderNo(),action.actionNo(),review == null ? null : review.claimNo(),action.kind(),
                review == null ? null : review.actualAmount().currency(),review == null ? null : review.actualAmount().minor(),
                review == null ? null : review.actualPaidAt().toEpochMilli(),action.operatorNo(),action.status(),
                review != null ? review.reason() : action.reconciliation() != null ? action.reconciliation().reason() : null,
                review != null && action.paymentNo()!=null ? action.acceptedAt() : null,action.paymentNo(),action.rejection(),
                json.writeValueAsString(action),approvalNo,time(action.acceptedAt()),action.operatorNo(),action.operatorNo());
        if (action.supplement()!=null) bindEvidence(tenant, action.order().orderNo(), action.actionNo(), action.supplement().evidenceKeys());
        String key = review != null ? review.evidenceKey() : action.reconciliation()!=null ? action.reconciliation().evidenceKey() : null;
        if (key != null) bindEvidence(tenant, action.order().orderNo(), action.actionNo(), List.of(key));
    }

    private void bindEvidence(String tenant, String order, String no, List<String> keys) {
        for (String key : keys)
            jdbc.update("""
                    UPDATE mall_offline_evidence SET action_no=?,updated_at=UTC_TIMESTAMP(3),version=version+1
                    WHERE tenant_no=? AND order_no=? AND asset_key=? AND action_no IS NULL
                    """, no,tenant,order,key);
    }
}
