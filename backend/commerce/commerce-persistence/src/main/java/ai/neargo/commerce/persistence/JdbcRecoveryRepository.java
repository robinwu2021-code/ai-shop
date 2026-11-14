package ai.neargo.commerce.persistence;

import ai.neargo.commerce.core.fulfillment.RecoveryRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import javax.sql.DataSource;

/** Identifier hints only; no mutable business decision is made from this scan. */
public final class JdbcRecoveryRepository implements RecoveryRepository {
    private final JdbcTemplate jdbc;

    public JdbcRecoveryRepository(DataSource ds) {
        jdbc = new JdbcTemplate(ds);
    }

    public List<Candidate> orders(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("invalid recovery page");
        return jdbc.query(
                "SELECT id,tenant_no,order_no FROM mall_order WHERE id>? ORDER BY id LIMIT ?",
                (rs, n) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3)),
                after,
                limit);
    }

    public List<String> pendingRefunds(String tenant, String order, int limit) {
        JdbcWriteGuard.tenant(tenant);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("invalid recovery page");
        return jdbc.queryForList(
                "SELECT refund_no FROM mall_refund WHERE tenant_no=? AND order_no=? AND status IN"
                    + " ('APPROVED','DISPATCHING','UNKNOWN') ORDER BY id LIMIT ?",
                String.class,
                tenant,
                order,
                limit);
    }
}
