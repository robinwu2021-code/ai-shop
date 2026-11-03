package ai.neargo.commerce.persistence.catalog;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class SysBusinessEndRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public SysBusinessEndRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(SysBusinessEndRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `sys_business_end`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`end_code`,`entitlement_code`,`name`,`scope`,`allowed_business_types`,`release_status`,`sale_enabled`,`bootstrap_required`,`enabled`,`route_policy_ref`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.endCode(),
                row.entitlementCode(),
                row.name(),
                row.scope(),
                row.allowedBusinessTypes(),
                row.releaseStatus(),
                row.saleEnabled(),
                row.bootstrapRequired(),
                row.enabled(),
                row.routePolicyRef());
    }

    public Optional<SysBusinessEndRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `sys_business_end` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new SysBusinessEndRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("end_code"),
                                        rs.getString("entitlement_code"),
                                        rs.getString("name"),
                                        rs.getString("scope"),
                                        rs.getString("allowed_business_types"),
                                        rs.getString("release_status"),
                                        rs.getObject("sale_enabled", Integer.class),
                                        rs.getObject("bootstrap_required", Integer.class),
                                        rs.getObject("enabled", Integer.class),
                                        rs.getString("route_policy_ref")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
