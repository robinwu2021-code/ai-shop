package ai.neargo.commerce.persistence.merchandise;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MallSkuVersionRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MallSkuVersionRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(MallSkuVersionRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `mall_sku_version`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`sku_version_no`,`sku_no`,`revision`,`status`,`currency`,`list_price_minor`,`sale_price_minor`,`allowed_business_types`,`refund_policy_snapshot`,`published_at`,`sku_name_snapshot`,`max_per_order_snapshot`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.skuVersionNo(),
                row.skuNo(),
                row.revision(),
                row.status(),
                row.currency(),
                row.listPriceMinor(),
                row.salePriceMinor(),
                row.allowedBusinessTypes(),
                row.refundPolicySnapshot(),
                row.publishedAt(),
                row.skuNameSnapshot(),
                row.maxPerOrderSnapshot());
    }

    public Optional<MallSkuVersionRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mall_sku_version` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new MallSkuVersionRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("sku_version_no"),
                                        rs.getString("sku_no"),
                                        rs.getObject("revision", Integer.class),
                                        rs.getString("status"),
                                        rs.getString("currency"),
                                        rs.getObject("list_price_minor", Long.class),
                                        rs.getObject("sale_price_minor", Long.class),
                                        rs.getString("allowed_business_types"),
                                        rs.getString("refund_policy_snapshot"),
                                        rs.getObject("published_at", Long.class),
                                        rs.getString("sku_name_snapshot"),
                                        rs.getObject("max_per_order_snapshot", Integer.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
