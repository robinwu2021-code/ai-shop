package ai.neargo.commerce.persistence.reliability;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class CommerceOutboxRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public CommerceOutboxRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(CommerceOutboxRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `commerce_outbox`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`event_no`,`aggregate_type`,`aggregate_no`,`aggregate_version`,`event_type`,`payload`,`status`,`retry_count`,`next_retry_at`,`schema_version`,`occurred_at`,`published_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.eventNo(),
                row.aggregateType(),
                row.aggregateNo(),
                row.aggregateVersion(),
                row.eventType(),
                row.payload(),
                row.status(),
                row.retryCount(),
                row.nextRetryAt(),
                row.schemaVersion(),
                row.occurredAt(),
                row.publishedAt());
    }

    public Optional<CommerceOutboxRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `commerce_outbox` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new CommerceOutboxRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("event_no"),
                                        rs.getString("aggregate_type"),
                                        rs.getString("aggregate_no"),
                                        rs.getObject("aggregate_version", Long.class),
                                        rs.getString("event_type"),
                                        rs.getString("payload"),
                                        rs.getString("status"),
                                        rs.getObject("retry_count", Integer.class),
                                        rs.getObject("next_retry_at", Long.class),
                                        rs.getObject("schema_version", Integer.class),
                                        rs.getObject("occurred_at", Long.class),
                                        rs.getObject("published_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
