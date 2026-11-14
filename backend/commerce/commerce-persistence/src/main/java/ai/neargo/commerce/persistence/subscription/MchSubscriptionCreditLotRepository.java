package ai.neargo.commerce.persistence.subscription;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionCreditLotRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchSubscriptionCreditLotRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MchSubscriptionCreditLotRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mch_subscription_credit_lot`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`credit_lot_no`,`store_no`,`currency`,`source_type`,`source_ref_no`,`issued_minor`,`remaining_minor`,`reserved_minor`,`parent_provenance`,`expires_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.creditLotNo(),
                row.storeNo(),
                row.currency(),
                row.sourceType(),
                row.sourceRefNo(),
                row.issuedMinor(),
                row.remainingMinor(),
                row.reservedMinor(),
                row.parentProvenance(),
                row.expiresAt());
    }

    public Optional<MchSubscriptionCreditLotRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_subscription_credit_lot` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchSubscriptionCreditLotRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("credit_lot_no"),
                                        rs.getString("store_no"),
                                        rs.getString("currency"),
                                        rs.getString("source_type"),
                                        rs.getString("source_ref_no"),
                                        rs.getObject("issued_minor", Long.class),
                                        rs.getObject("remaining_minor", Long.class),
                                        rs.getObject("reserved_minor", Long.class),
                                        rs.getString("parent_provenance"),
                                        rs.getObject("expires_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
