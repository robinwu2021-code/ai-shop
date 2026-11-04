package ai.neargo.commerce.persistence.subscription;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionPeriodRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchSubscriptionPeriodRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MchSubscriptionPeriodRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mch_subscription_period`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`period_no`,`store_no`,`entity_no`,`business_type_code`,`stream_key`,`source_type`,`source_ref_no`,`fulfillment_task_no`,`plan_no`,`plan_version_no`,`standalone_entitlement_code`,`tier_code`,`delivered_at`,`delivery_seq`,`granted_duration_ms`,`revoked_duration_ms`,`original_start_at`,`original_end_at`,`effective_start_at`,`effective_end_at`,`eligible_basis_minor`,`cash_basis_minor`,`credit_basis_minor`,`currency`,`state`,`converted_by_no`,`source_policy_snapshot`,`revoked_by_no`,`revocation_snapshot`)"
                    + " VALUES"
                    + " (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.periodNo(),
                row.storeNo(),
                row.entityNo(),
                row.businessTypeCode(),
                row.streamKey(),
                row.sourceType(),
                row.sourceRefNo(),
                row.fulfillmentTaskNo(),
                row.planNo(),
                row.planVersionNo(),
                row.standaloneEntitlementCode(),
                row.tierCode(),
                row.deliveredAt(),
                row.deliverySeq(),
                row.grantedDurationMs(),
                row.revokedDurationMs(),
                row.originalStartAt(),
                row.originalEndAt(),
                row.effectiveStartAt(),
                row.effectiveEndAt(),
                row.eligibleBasisMinor(),
                row.cashBasisMinor(),
                row.creditBasisMinor(),
                row.currency(),
                row.state(),
                row.convertedByNo(),
                row.sourcePolicySnapshot(),row.revokedByNo(),row.revocationSnapshot());
    }

    public Optional<MchSubscriptionPeriodRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_subscription_period` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchSubscriptionPeriodRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("period_no"),
                                        rs.getString("store_no"),
                                        rs.getString("entity_no"),
                                        rs.getString("business_type_code"),
                                        rs.getString("stream_key"),
                                        rs.getString("source_type"),
                                        rs.getString("source_ref_no"),
                                        rs.getString("fulfillment_task_no"),
                                        rs.getString("plan_no"),
                                        rs.getString("plan_version_no"),
                                        rs.getString("standalone_entitlement_code"),
                                        rs.getString("tier_code"),
                                        rs.getObject("delivered_at", Long.class),
                                        rs.getObject("delivery_seq", Long.class),
                                        rs.getObject("granted_duration_ms", Long.class),
                                        rs.getObject("revoked_duration_ms", Long.class),
                                        rs.getObject("original_start_at", Long.class),
                                        rs.getObject("original_end_at", Long.class),
                                        rs.getObject("effective_start_at", Long.class),
                                        rs.getObject("effective_end_at", Long.class),
                                        rs.getObject("eligible_basis_minor", Long.class),
                                        rs.getObject("cash_basis_minor", Long.class),
                                        rs.getObject("credit_basis_minor", Long.class),
                                        rs.getString("currency"),
                                        rs.getString("state"),
                                        rs.getString("converted_by_no"),
                                        rs.getString("source_policy_snapshot"),rs.getString("revoked_by_no"),rs.getString("revocation_snapshot")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
