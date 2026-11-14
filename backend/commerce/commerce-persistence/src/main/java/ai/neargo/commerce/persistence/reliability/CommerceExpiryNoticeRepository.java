package ai.neargo.commerce.persistence.reliability;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class CommerceExpiryNoticeRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public CommerceExpiryNoticeRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(CommerceExpiryNoticeRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `commerce_expiry_notice`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`notice_no`,`store_no`,`entitlement_code`,`coverage_end_at`,`threshold_code`,`due_at`,`expires_at`,`policy_version`,`observed_entitlement_version`,`recipient_type`,`recipient_no`,`channel`,`dedup_key`,`status`,`attempts`,`next_attempt_at`,`lease_until`,`provider_message_no`,`last_error_code`,`sent_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.noticeNo(),
                row.storeNo(),
                row.entitlementCode(),
                row.coverageEndAt(),
                row.thresholdCode(),
                row.dueAt(),
                row.expiresAt(),
                row.policyVersion(),
                row.observedEntitlementVersion(),
                row.recipientType(),
                row.recipientNo(),
                row.channel(),
                row.dedupKey(),
                row.status(),
                row.attempts(),
                row.nextAttemptAt(),
                row.leaseUntil(),
                row.providerMessageNo(),
                row.lastErrorCode(),
                row.sentAt());
    }

    public Optional<CommerceExpiryNoticeRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `commerce_expiry_notice` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new CommerceExpiryNoticeRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("notice_no"),
                                        rs.getString("store_no"),
                                        rs.getString("entitlement_code"),
                                        rs.getObject("coverage_end_at", Long.class),
                                        rs.getString("threshold_code"),
                                        rs.getObject("due_at", Long.class),
                                        rs.getObject("expires_at", Long.class),
                                        rs.getString("policy_version"),
                                        rs.getObject("observed_entitlement_version", Long.class),
                                        rs.getString("recipient_type"),
                                        rs.getString("recipient_no"),
                                        rs.getString("channel"),
                                        rs.getString("dedup_key"),
                                        rs.getString("status"),
                                        rs.getObject("attempts", Integer.class),
                                        rs.getObject("next_attempt_at", Long.class),
                                        rs.getObject("lease_until", Long.class),
                                        rs.getString("provider_message_no"),
                                        rs.getString("last_error_code"),
                                        rs.getObject("sent_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
