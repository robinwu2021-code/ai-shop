package ai.neargo.commerce.persistence.trade;

import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallRefundRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MallRefundRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public void insert(MallRefundRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        jdbc.update(
                "INSERT INTO `mall_refund`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`refund_no`,`order_no`,`store_no`,`reason`,`policy_snapshot`,`requested_cash_minor`,`approved_cash_minor`,`restore_credit_minor`,`pay_refund_no`,`status`,`idempotency_key`,`reviewed_by`,`reviewed_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.refundNo(),
                row.orderNo(),
                row.storeNo(),
                row.reason(),
                row.policySnapshot(),
                row.requestedCashMinor(),
                row.approvedCashMinor(),
                row.restoreCreditMinor(),
                row.payRefundNo(),
                row.status(),
                row.idempotencyKey(),
                row.reviewedBy(),
                row.reviewedAt());
    }

    public Optional<MallRefundRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mall_refund` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MallRefundRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("refund_no"),
                                        rs.getString("order_no"),
                                        rs.getString("store_no"),
                                        rs.getString("reason"),
                                        rs.getString("policy_snapshot"),
                                        rs.getObject("requested_cash_minor", Long.class),
                                        rs.getObject("approved_cash_minor", Long.class),
                                        rs.getObject("restore_credit_minor", Long.class),
                                        rs.getString("pay_refund_no"),
                                        rs.getString("status"),
                                        rs.getString("idempotency_key"),
                                        rs.getString("reviewed_by"),
                                        rs.getObject("reviewed_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
