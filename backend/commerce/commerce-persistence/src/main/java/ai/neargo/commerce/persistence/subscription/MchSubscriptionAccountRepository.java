package ai.neargo.commerce.persistence.subscription;

import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionAccountRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchSubscriptionAccountRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public void insert(MchSubscriptionAccountRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        jdbc.update(
                "INSERT INTO `mch_subscription_account`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`store_no`,`entity_no`,`business_type_code`,`entitlement_version`,`pending_change_order_no`,`legacy_contract_ref`,`migration_batch_no`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.storeNo(),
                row.entityNo(),
                row.businessTypeCode(),
                row.entitlementVersion(),
                row.pendingChangeOrderNo(),
                row.legacyContractRef(),
                row.migrationBatchNo());
    }

    public Optional<MchSubscriptionAccountRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_subscription_account` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchSubscriptionAccountRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("store_no"),
                                        rs.getString("entity_no"),
                                        rs.getString("business_type_code"),
                                        rs.getObject("entitlement_version", Long.class),
                                        rs.getString("pending_change_order_no"),
                                        rs.getString("legacy_contract_ref"),
                                        rs.getString("migration_batch_no")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
