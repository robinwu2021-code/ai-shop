package ai.neargo.commerce.persistence;

import ai.neargo.commerce.core.offline.OfflineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.util.*;

public final class JdbcOfflineRepository implements OfflineRepository {
    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public JdbcOfflineRepository(DataSource ds) { this.ds=ds;this.jdbc=new JdbcTemplate(ds); }
    public void reserve(Evidence e) {
        JdbcWriteGuard.require(ds,e.scope().store().tenantNo());
        jdbc.update("INSERT INTO mall_offline_evidence(tenant_no,asset_key,order_no,scope_snapshot,created_at) VALUES (?,?,?,?,?)",
                e.scope().store().tenantNo(),e.assetKey(),e.scope().orderNo(),json.writeValueAsString(e),e.createdAt());
    }
    public Optional<Evidence> evidence(String t,String key) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.query("SELECT scope_snapshot FROM mall_offline_evidence WHERE tenant_no=? AND asset_key=? FOR UPDATE",
                (r,n)->json.readValue(r.getString(1),Evidence.class),t,key).stream().findFirst();
    }
    public List<String> retained(String t) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.queryForList("SELECT asset_key FROM mall_offline_evidence WHERE tenant_no=?",String.class,t);
    }
    public Optional<Claim> claim(String t,String order,String no) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.query("SELECT claim_snapshot FROM mall_offline_claim WHERE tenant_no=? AND order_no=? AND claim_no=? FOR UPDATE",
                (r,n)->json.readValue(r.getString(1),Claim.class),t,order,no).stream().findFirst();
    }
    public void saveClaim(Claim c) {
        var q=c.command().order();JdbcWriteGuard.require(ds,q.store().tenantNo());
        jdbc.update("INSERT INTO mall_offline_claim(tenant_no,order_no,claim_no,claim_snapshot) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE claim_snapshot=VALUES(claim_snapshot)",
                q.store().tenantNo(),q.orderNo(),c.command().claimNo(),json.writeValueAsString(c));
    }
    public List<Claim> claims(String t,String order) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.query("SELECT claim_snapshot FROM mall_offline_claim WHERE tenant_no=? AND order_no=? ORDER BY claim_no",
                (r,n)->json.readValue(r.getString(1),Claim.class),t,order);
    }
    public List<Claim> submittedClaims(String t,String afterOrder,String afterClaim,int limit) {
        JdbcWriteGuard.require(ds,t);
        String o=afterOrder==null?"":afterOrder,c=afterClaim==null?"":afterClaim;
        return jdbc.query("SELECT claim_snapshot FROM mall_offline_claim WHERE tenant_no=?"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(claim_snapshot,'$.status'))='SUBMITTED'"
                + " AND (order_no>? OR order_no=? AND claim_no>?) ORDER BY order_no,claim_no LIMIT ?",
                (r,n)->json.readValue(r.getString(1),Claim.class),t,o,o,c,limit);
    }
    public Optional<Action> action(String t,String order,String no) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.query("SELECT action_snapshot FROM mall_offline_action WHERE tenant_no=? AND order_no=? AND action_no=? FOR UPDATE",
                (r,n)->json.readValue(r.getString(1),Action.class),t,order,no).stream().findFirst();
    }
    public List<Action> pending(String t,String order) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.query("SELECT action_snapshot FROM mall_offline_action WHERE tenant_no=? AND order_no=? AND status='ACCEPTED' ORDER BY action_no FOR UPDATE",
                (r,n)->json.readValue(r.getString(1),Action.class),t,order);
    }
    public List<String> pendingOrders(String t,String after,int limit) {
        JdbcWriteGuard.require(ds,t);
        return jdbc.queryForList("SELECT DISTINCT order_no FROM mall_offline_action WHERE status='ACCEPTED' AND tenant_no=? AND order_no>? ORDER BY order_no LIMIT ?",
                String.class,t,after==null?"":after,limit);
    }
    public void saveAction(Action a) {
        var q=a.order();JdbcWriteGuard.require(ds,q.store().tenantNo());
        jdbc.update("INSERT INTO mall_offline_action(tenant_no,order_no,action_no,status,action_snapshot) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),action_snapshot=VALUES(action_snapshot)",
                q.store().tenantNo(),q.orderNo(),a.actionNo(),a.status(),json.writeValueAsString(a));
    }

}
