package ai.neargo.commerce.persistence.subscription;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionCreditLedgerRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchSubscriptionCreditLedgerRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MchSubscriptionCreditLedgerRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mch_subscription_credit_ledger`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`ledger_no`,`store_no`,`currency`,`credit_lot_no`,`event_type`,`biz_ref_no`,`event_seq`,`amount_minor`,`balance_delta_minor`,`reserved_delta_minor`,`order_component_no`,`reversal_of_ledger_no`,`reason`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.ledgerNo(),
                row.storeNo(),
                row.currency(),
                row.creditLotNo(),
                row.eventType(),
                row.bizRefNo(),
                row.eventSeq(),
                row.amountMinor(),
                row.balanceDeltaMinor(),
                row.reservedDeltaMinor(),
                row.orderComponentNo(),
                row.reversalOfLedgerNo(),
                row.reason());
    }

    public Optional<MchSubscriptionCreditLedgerRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_subscription_credit_ledger` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchSubscriptionCreditLedgerRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("ledger_no"),
                                        rs.getString("store_no"),
                                        rs.getString("currency"),
                                        rs.getString("credit_lot_no"),
                                        rs.getString("event_type"),
                                        rs.getString("biz_ref_no"),
                                        rs.getObject("event_seq", Integer.class),
                                        rs.getObject("amount_minor", Long.class),
                                        rs.getObject("balance_delta_minor", Long.class),
                                        rs.getObject("reserved_delta_minor", Long.class),
                                        rs.getString("order_component_no"),
                                        rs.getString("reversal_of_ledger_no"),
                                        rs.getString("reason")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
