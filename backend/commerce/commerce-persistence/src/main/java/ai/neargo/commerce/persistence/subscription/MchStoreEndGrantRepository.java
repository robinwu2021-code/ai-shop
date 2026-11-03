package ai.neargo.commerce.persistence.subscription;

import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchStoreEndGrantRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchStoreEndGrantRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public void insert(MchStoreEndGrantRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        jdbc.update(
                "INSERT INTO `mch_store_end_grant`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`grant_no`,`store_no`,`period_no`,`end_code`,`entitlement_code`,`schedule_revision`,`segment_seq`,`schedule_event_no`,`start_at`,`end_at`,`state`,`revoked_at`,`reason`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.grantNo(),
                row.storeNo(),
                row.periodNo(),
                row.endCode(),
                row.entitlementCode(),
                row.scheduleRevision(),
                row.segmentSeq(),
                row.scheduleEventNo(),
                row.startAt(),
                row.endAt(),
                row.state(),
                row.revokedAt(),
                row.reason());
    }

    public Optional<MchStoreEndGrantRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_store_end_grant` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MchStoreEndGrantRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("grant_no"),
                                        rs.getString("store_no"),
                                        rs.getString("period_no"),
                                        rs.getString("end_code"),
                                        rs.getString("entitlement_code"),
                                        rs.getObject("schedule_revision", Long.class),
                                        rs.getObject("segment_seq", Integer.class),
                                        rs.getString("schedule_event_no"),
                                        rs.getObject("start_at", Long.class),
                                        rs.getObject("end_at", Long.class),
                                        rs.getString("state"),
                                        rs.getObject("revoked_at", Long.class),
                                        rs.getString("reason")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
