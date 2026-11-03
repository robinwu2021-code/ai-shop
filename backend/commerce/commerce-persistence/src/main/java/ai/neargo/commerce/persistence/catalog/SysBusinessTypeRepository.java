package ai.neargo.commerce.persistence.catalog;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class SysBusinessTypeRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public SysBusinessTypeRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(SysBusinessTypeRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `sys_business_type`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`business_type_code`,`name`,`enabled`,`sort`,`plan_catalog_version`,`remark`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.businessTypeCode(),
                row.name(),
                row.enabled(),
                row.sort(),
                row.planCatalogVersion(),
                row.remark());
    }

    public Optional<SysBusinessTypeRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `sys_business_type` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new SysBusinessTypeRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("business_type_code"),
                                        rs.getString("name"),
                                        rs.getObject("enabled", Integer.class),
                                        rs.getObject("sort", Integer.class),
                                        rs.getObject("plan_catalog_version", Long.class),
                                        rs.getString("remark")),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
