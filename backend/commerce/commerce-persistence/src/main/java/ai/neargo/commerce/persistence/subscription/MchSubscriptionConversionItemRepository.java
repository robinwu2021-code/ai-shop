package ai.neargo.commerce.persistence.subscription;

import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class MchSubscriptionConversionItemRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public MchSubscriptionConversionItemRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public void insert(MchSubscriptionConversionItemRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        jdbc.update(
                "INSERT INTO `mch_subscription_conversion_item`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`conversion_no`,`source_period_no`,`basis_minor`,`original_duration_ms`,`remaining_duration_ms`,`converted_minor`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.conversionNo(),
                row.sourcePeriodNo(),
                row.basisMinor(),
                row.originalDurationMs(),
                row.remainingDurationMs(),
                row.convertedMinor());
    }

    public Optional<MchSubscriptionConversionItemRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `mch_subscription_conversion_item` WHERE tenant_no=? AND"
                            + " id=?",
                        (rs, n) ->
                                new MchSubscriptionConversionItemRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("conversion_no"),
                                        rs.getString("source_period_no"),
                                        rs.getObject("basis_minor", Long.class),
                                        rs.getObject("original_duration_ms", Long.class),
                                        rs.getObject("remaining_duration_ms", Long.class),
                                        rs.getObject("converted_minor", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
