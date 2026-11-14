package ai.neargo.commerce.persistence.trade;

import ai.neargo.commerce.persistence.JdbcInsert;
import ai.neargo.commerce.persistence.JdbcWriteGuard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** All operations require a tenant; inserts participate in the caller's local transaction. */
public final class CommerceEventInboxRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public CommerceEventInboxRepository(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    public long insert(CommerceEventInboxRecord row) {
        JdbcWriteGuard.require(dataSource, row.tenantNo());
        return JdbcInsert.insert(
                jdbc,
                "INSERT INTO `commerce_event_inbox`"
                    + " (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,`source_system`,`event_id`,`event_type`,`aggregate_no`,`payload_hash`,`status`,`received_at`,`processed_at`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.tenantNo(),
                row.createdAt(),
                row.updatedAt(),
                row.createdBy(),
                row.updatedBy(),
                row.version(),
                row.deleted(),
                row.sourceSystem(),
                row.eventId(),
                row.eventType(),
                row.aggregateNo(),
                row.payloadHash(),
                row.status(),
                row.receivedAt(),
                row.processedAt());
    }

    public Optional<CommerceEventInboxRecord> findById(String tenantNo, long id) {
        JdbcWriteGuard.tenant(tenantNo);
        return jdbc
                .query(
                        "SELECT * FROM `commerce_event_inbox` WHERE tenant_no=? AND id=?",
                        (rs, n) ->
                                new CommerceEventInboxRecord(
                                        rs.getObject("id", Long.class),
                                        rs.getString("tenant_no"),
                                        rs.getObject("created_at", java.time.LocalDateTime.class),
                                        rs.getObject("updated_at", java.time.LocalDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("updated_by"),
                                        rs.getObject("version", Long.class),
                                        rs.getObject("deleted", Integer.class),
                                        rs.getString("source_system"),
                                        rs.getString("event_id"),
                                        rs.getString("event_type"),
                                        rs.getString("aggregate_no"),
                                        rs.getString("payload_hash"),
                                        rs.getString("status"),
                                        rs.getObject("received_at", Long.class),
                                        rs.getObject("processed_at", Long.class)),
                        tenantNo,
                        id)
                .stream()
                .findFirst();
    }
}
