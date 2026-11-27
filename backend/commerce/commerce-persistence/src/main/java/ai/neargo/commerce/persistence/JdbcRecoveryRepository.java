package ai.neargo.commerce.persistence;

import ai.neargo.commerce.core.fulfillment.RecoveryRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import javax.sql.DataSource;

/** Identifier hints only; no mutable business decision is made from this scan. */
public final class JdbcRecoveryRepository implements RecoveryRepository {
    private final JdbcTemplate jdbc;

    private final boolean mallDatabase;

    public JdbcRecoveryRepository(DataSource ds) { this(ds,false); }

    public JdbcRecoveryRepository(DataSource ds, boolean mallDatabase) {
        this.mallDatabase = mallDatabase;
        jdbc = new JdbcTemplate(ds);
    }

    public void expireQuotes(int limit) {
        if (!mallDatabase) return;
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("invalid recovery page");
        jdbc.update("""
                UPDATE mall_order SET stage='QUOTE_EXPIRED',updated_at=UTC_TIMESTAMP(3),version=version+1
                WHERE stage='QUOTE' AND quote_expires_at<=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
                ORDER BY quote_expires_at,id LIMIT ?
                """,limit);
    }

    public List<Candidate> orders(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("invalid recovery page");
        return jdbc.query(
                "SELECT id,tenant_no,order_no FROM mall_order WHERE id>?"
                        + (mallDatabase ? " AND stage='ORDERED'" : "") + " ORDER BY id LIMIT ?",
                (rs, n) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3)),
                after,
                limit);
    }

    public List<String> pendingRefunds(String tenant, String order, int limit) {
        JdbcWriteGuard.tenant(tenant);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("invalid recovery page");
        return jdbc.queryForList(
                "SELECT refund_no FROM mall_refund WHERE tenant_no=? AND order_no=?"
                    + (mallDatabase ? " AND JSON_CONTAINS_PATH(policy_snapshot,'one','$.refundNo')" : "")
                    + " AND status IN"
                    + " ('APPROVED','DISPATCHING','UNKNOWN') ORDER BY id LIMIT ?",
                String.class,
                tenant,
                order,
                limit);
    }
}
