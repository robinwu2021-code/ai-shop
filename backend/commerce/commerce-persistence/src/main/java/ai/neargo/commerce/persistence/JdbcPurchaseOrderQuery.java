package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.mall.reporting.PurchaseOrderQueryPort;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Financial history queries use persisted order ownership, including deleted merchant/store history. */
public final class JdbcPurchaseOrderQuery implements PurchaseOrderQueryPort {
    private final JdbcTemplate jdbc;
    private static final String CLAIM_STATUS = "JSON_UNQUOTE(JSON_EXTRACT(c.claim_snapshot,'$.status'))";
    private static final String PENDING = "EXISTS (SELECT 1 FROM mall_offline_claim c WHERE c.tenant_no=o.tenant_no"
            + " AND c.order_no=o.order_no AND " + CLAIM_STATUS + " IN ('SUBMITTED','REVIEWING'))";
    private static final String SELECT = "SELECT o.order_no,o.entity_no,o.store_no,o.currency,o.cash_due_minor,o.pay_status,o.created_at,"
            + "(SELECT " + CLAIM_STATUS + " FROM mall_offline_claim c WHERE c.tenant_no=o.tenant_no AND c.order_no=o.order_no"
            + " ORDER BY CASE WHEN " + CLAIM_STATUS + " IN ('SUBMITTED','REVIEWING') THEN 0 ELSE 1 END,"
            + " JSON_EXTRACT(c.claim_snapshot,'$.submittedAt') DESC,c.claim_no DESC LIMIT 1) claim_status FROM mall_order o WHERE o.tenant_no=?";

    public JdbcPurchaseOrderQuery(DataSource ds) { jdbc = new JdbcTemplate(ds); }

    @Override public List<Row> search(Query q) {
        var a=q.access(); var f=q.filter();
        if (!a.allEntities() && a.entityNos().isEmpty() || f.entityNo()!=null && !a.allows(f.entityNo())) return List.of();
        StringBuilder sql=new StringBuilder(SELECT); var args=new ArrayList<Object>(); args.add(a.tenantNo());
        if (!a.allEntities()) { sql.append(" AND o.entity_no IN (").append(marks(a.entityNos().size())).append(')'); args.addAll(a.entityNos()); }
        equal(sql,args,"o.entity_no",f.entityNo()); equal(sql,args,"o.store_no",f.storeNo());
        equal(sql,args,"o.order_no",f.orderNo()); equal(sql,args,"o.pay_status",f.status()); equal(sql,args,"o.currency",f.currency());
        if (f.createdFrom()!=null) { sql.append(" AND o.created_at>=?"); args.add(at(f.createdFrom())); }
        if (f.createdTo()!=null) { sql.append(" AND o.created_at<?"); args.add(at(f.createdTo())); }
        if (f.pendingOnly()) sql.append(" AND ").append(PENDING);
        if (q.after()!=null) {
            var c=q.after(); sql.append(" AND (o.created_at<? OR (o.created_at=? AND ");
            args.add(at(c.createdAt())); args.add(at(c.createdAt()));
            if (c.model()==Model.ENTITY) sql.append("1=1");
            else { sql.append("o.order_no<?"); args.add(c.orderNo()); }
            sql.append("))");
        }
        sql.append(" ORDER BY o.created_at DESC,o.order_no DESC LIMIT ?"); args.add(q.limit());
        return jdbc.query(sql.toString(),this::row,args.toArray());
    }

    @Override public List<Row> findByOrderNos(String tenant, List<String> nos) {
        if (tenant==null || tenant.isBlank() || nos==null || nos.size()>500) throw new IllegalArgumentException("attribution");
        if (nos.isEmpty()) return List.of();
        var args=new ArrayList<Object>();args.add(tenant);args.addAll(nos);
        return jdbc.query(SELECT+" AND o.order_no IN ("+marks(nos.size())+") ORDER BY o.created_at DESC,o.order_no DESC",this::row,args.toArray());
    }
    private Row row(ResultSet r,int i) throws SQLException {
        return new Row(Model.STORE,r.getString("order_no"),r.getString("entity_no"),r.getString("store_no"),
                r.getString("currency"),Long.toString(r.getLong("cash_due_minor")),r.getString("pay_status"),
                r.getString("claim_status"),r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli());
    }
    private static LocalDateTime at(long millis) { return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis),ZoneOffset.UTC); }
    private static String marks(int n) { return String.join(",",Collections.nCopies(n,"?")); }
    private static void equal(StringBuilder sql,List<Object> args,String column,String value) {
        if(value!=null) { sql.append(" AND ").append(column).append("=?"); args.add(value); }
    }
}
