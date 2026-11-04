package ai.neargo.commerce.persistence.catalog;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class SysPlanVersionEndRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public SysPlanVersionEndRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(SysPlanVersionEndRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `sys_plan_version_end`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`plan_version_no`,`end_code`,`entitlement_code`,`sort`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.planVersionNo(),
                row.endCode(),
                row.entitlementCode(),
                row.sort());
    }

    public Optional<SysPlanVersionEndRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `sys_plan_version_end` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new SysPlanVersionEndRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("plan_version_no"),
                                        rs.getString("end_code"),
                                        rs.getString("entitlement_code"),
                                        rs.getObject("sort", Integer.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
