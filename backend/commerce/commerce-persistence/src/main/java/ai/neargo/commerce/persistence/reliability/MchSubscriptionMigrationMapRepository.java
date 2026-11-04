package ai.neargo.commerce.persistence.reliability;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionMigrationMapRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchSubscriptionMigrationMapRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MchSubscriptionMigrationMapRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mch_subscription_migration_map`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`migration_batch_no`,`legacy_entity_plan_id`,`legacy_contract_ref`,`store_no`,`new_period_no`,`allocated_basis_minor`,`evidence_ref`,`legacy_snapshot`,`validation_status`,`cutover_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.migrationBatchNo(),
                row.legacyEntityPlanId(),
                row.legacyContractRef(),
                row.storeNo(),
                row.newPeriodNo(),
                row.allocatedBasisMinor(),
                row.evidenceRef(),
                row.legacySnapshot(),
                row.validationStatus(),
                row.cutoverAt());
    }

    public Optional<MchSubscriptionMigrationMapRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_subscription_migration_map` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchSubscriptionMigrationMapRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("migration_batch_no"),
                                        rs.getObject("legacy_entity_plan_id", Long.class),
                                        rs.getString("legacy_contract_ref"),
                                        rs.getString("store_no"),
                                        rs.getString("new_period_no"),
                                        rs.getObject("allocated_basis_minor", Long.class),
                                        rs.getString("evidence_ref"),
                                        rs.getString("legacy_snapshot"),
                                        rs.getString("validation_status"),
                                        rs.getObject("cutover_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
