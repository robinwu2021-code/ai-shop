package ai.neargo.commerce.persistence.merchandise;

import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallSkuRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MallSkuRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public void insert(MallSkuRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        jdbc.update(
                "INSERT INTO `mall_sku`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`sku_no`,`product_no`,`sku_name`,`current_version_no`,`sale_status`,`max_per_order`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.skuNo(),
                row.productNo(),
                row.skuName(),
                row.currentVersionNo(),
                row.saleStatus(),
                row.maxPerOrder());
    }

    public Optional<MallSkuRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mall_sku` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MallSkuRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("sku_no"),
                                        rs.getString("product_no"),
                                        rs.getString("sku_name"),
                                        rs.getString("current_version_no"),
                                        rs.getString("sale_status"),
                                        rs.getObject("max_per_order", Integer.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
