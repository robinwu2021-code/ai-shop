package ai.neargo.commerce.persistence.reliability;

import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchEndScheduleEventRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchEndScheduleEventRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public void insert(MchEndScheduleEventRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        jdbc.update(
                "INSERT INTO `mch_end_schedule_event`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`schedule_event_no`,`store_no`,`entitlement_code`,`entitlement_version`,`operation_ref_no`,`reason_type`,`effective_at`,`before_snapshot`,`after_snapshot`,`duration_check_snapshot`,`stacking_policy_version`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.scheduleEventNo(),
                row.storeNo(),
                row.entitlementCode(),
                row.entitlementVersion(),
                row.operationRefNo(),
                row.reasonType(),
                row.effectiveAt(),
                row.beforeSnapshot(),
                row.afterSnapshot(),
                row.durationCheckSnapshot(),
                row.stackingPolicyVersion());
    }

    public Optional<MchEndScheduleEventRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_end_schedule_event` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchEndScheduleEventRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("schedule_event_no"),
                                        rs.getString("store_no"),
                                        rs.getString("entitlement_code"),
                                        rs.getObject("entitlement_version", Long.class),
                                        rs.getString("operation_ref_no"),
                                        rs.getString("reason_type"),
                                        rs.getObject("effective_at", Long.class),
                                        rs.getString("before_snapshot"),
                                        rs.getString("after_snapshot"),
                                        rs.getString("duration_check_snapshot"),
                                        rs.getString("stacking_policy_version")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
