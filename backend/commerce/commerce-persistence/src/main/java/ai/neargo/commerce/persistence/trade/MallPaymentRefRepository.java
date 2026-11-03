package ai.neargo.commerce.persistence.trade;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallPaymentRefRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MallPaymentRefRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MallPaymentRefRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mall_payment_ref`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`payment_attempt_no`,`order_no`,`pay_order_no`,`receiver_account_no`,`payment_business_type`,`currency`,`amount_minor`,`status`,`provider_paid_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.paymentAttemptNo(),
                row.orderNo(),
                row.payOrderNo(),
                row.receiverAccountNo(),
                row.paymentBusinessType(),
                row.currency(),
                row.amountMinor(),
                row.status(),
                row.providerPaidAt());
    }

    public Optional<MallPaymentRefRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mall_payment_ref` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MallPaymentRefRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("payment_attempt_no"),
                                        rs.getString("order_no"),
                                        rs.getString("pay_order_no"),
                                        rs.getString("receiver_account_no"),
                                        rs.getString("payment_business_type"),
                                        rs.getString("currency"),
                                        rs.getObject("amount_minor", Long.class),
                                        rs.getString("status"),
                                        rs.getObject("provider_paid_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
