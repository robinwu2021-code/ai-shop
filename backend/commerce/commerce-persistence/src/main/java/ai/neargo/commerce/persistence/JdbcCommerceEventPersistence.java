package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.port.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** The sole commerce event writer. No host outbox writer or external publish is invoked. */
public final class JdbcCommerceEventPersistence implements CommerceEventPersistencePort {
    private final DataSource ds;
    private final JdbcTemplate jdbc;

    public JdbcCommerceEventPersistence(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
        jdbc = new JdbcTemplate(ds);
    }

    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    public boolean append(CommerceEvent e) {
        JdbcWriteGuard.require(ds, e.tenantNo());
        try {
            jdbc.update(
                    "INSERT INTO commerce_outbox"
                        + " (tenant_no,created_at,updated_at,version,deleted,event_no,aggregate_type,aggregate_no,aggregate_version,event_type,payload,status,retry_count,schema_version,occurred_at,next_retry_at)"
                        + " VALUES (?,?,?,0,0,?,?,?,?,?,CAST(? AS JSON),'PENDING',0,?,?,?)",
                    e.tenantNo(),
                    now(),
                    now(),
                    e.eventNo(),
                    e.aggregateType(),
                    e.aggregateNo(),
                    e.aggregateVersion(),
                    e.eventType(),
                    e.payloadJson(),
                    e.schemaVersion(),
                    e.occurredAt().toEpochMilli(),
                    e.occurredAt().toEpochMilli());
            return true;
        } catch (DuplicateKeyException duplicate) {
            var same =
                    jdbc.query(
                            "SELECT event_no FROM commerce_outbox WHERE tenant_no=? AND event_no=?"
                                + " AND aggregate_type=? AND aggregate_no=? AND aggregate_version=?"
                                + " AND event_type=? AND schema_version=? AND occurred_at=? AND"
                                + " payload=CAST(? AS JSON) FOR UPDATE",
                            (rs, n) -> rs.getString(1),
                            e.tenantNo(),
                            e.eventNo(),
                            e.aggregateType(),
                            e.aggregateNo(),
                            e.aggregateVersion(),
                            e.eventType(),
                            e.schemaVersion(),
                            e.occurredAt().toEpochMilli(),
                            e.payloadJson());
            if (same.isEmpty())
                throw new IllegalStateException("Conflicting commerce event identity", duplicate);
            return false;
        }
    }

    public boolean recordInbound(InboundFact f) {
        JdbcWriteGuard.require(ds, f.tenantNo());
        try {
            insertInbound(f);
            return true;
        } catch (DuplicateKeyException duplicate) {
            var same =
                    jdbc.query(
                            "SELECT event_id FROM commerce_event_inbox WHERE tenant_no=? AND"
                                    + " source_system=? AND event_id=? AND event_type=? AND"
                                    + " aggregate_no=? AND payload_hash=? FOR UPDATE",
                            (rs, n) -> rs.getString(1),
                            f.tenantNo(),
                            f.producer(),
                            f.factNo(),
                            f.eventType(),
                            f.aggregateNo(),
                            f.payloadDigest());
            if (same.isEmpty())
                throw new IllegalStateException("Conflicting inbound fact identity", duplicate);
            return false;
        }
    }

    private void insertInbound(InboundFact f) {
        jdbc.update(
                "INSERT INTO commerce_event_inbox"
                    + " (tenant_no,created_at,updated_at,version,deleted,source_system,event_id,event_type,aggregate_no,payload_hash,status,received_at)"
                    + " VALUES (?,?,?,0,0,?,?,?,?,?,'RECEIVED',?)",
                f.tenantNo(),
                now(),
                now(),
                f.producer(),
                f.factNo(),
                f.eventType(),
                f.aggregateNo(),
                f.payloadDigest(),
                f.receivedAt().toEpochMilli());
    }

    /**
     * Allocates the host branch number once, before store INSERT, in the same owner transaction.
     */
    public Optional<String> reserveStoreCreation(
            String tenant, String scopeKey, String payloadHash, String proposedStore) {
        JdbcWriteGuard.require(ds, tenant);
        try {
            insertInbound(
                    new InboundFact(
                            tenant,
                            "STORE_CREATION",
                            scopeKey,
                            "CREATE_BRANCH",
                            proposedStore,
                            payloadHash,
                            Instant.now()));
            return Optional.empty();
        } catch (DuplicateKeyException duplicate) {
            var existing =
                    jdbc.queryForList(
                            "SELECT aggregate_no FROM commerce_event_inbox WHERE tenant_no=? AND"
                                + " source_system='STORE_CREATION' AND event_id=? AND"
                                + " event_type='CREATE_BRANCH' AND payload_hash=? FOR UPDATE",
                            String.class,
                            tenant,
                            scopeKey,
                            payloadHash);
            if (existing.isEmpty()) throw new StoreCreationConflictException();
            return Optional.of(existing.getFirst());
        }
    }

    public static final class StoreCreationConflictException extends IllegalArgumentException {}

    private record Pending(long id, CommerceEvent event) {}

    public EventPage pending(EventQuery q) {
        JdbcWriteGuard.tenant(q.tenantNo());
        if (q.limit() < 1 || q.limit() > 1000)
            throw new IllegalArgumentException("limit must be 1..1000");
        long cursor = q.afterCursor() == null ? 0 : Long.parseLong(q.afterCursor());
        if (cursor < 0) throw new IllegalArgumentException("negative cursor");
        var rows =
                jdbc.query(
                        "SELECT * FROM commerce_outbox WHERE tenant_no=? AND status='PENDING' AND"
                                + " id>? ORDER BY id LIMIT ?",
                        (rs, n) ->
                                new Pending(
                                        rs.getLong("id"),
                                        new CommerceEvent(
                                                rs.getString("event_no"),
                                                rs.getString("tenant_no"),
                                                rs.getString("aggregate_type"),
                                                rs.getString("aggregate_no"),
                                                rs.getLong("aggregate_version"),
                                                rs.getString("event_type"),
                                                rs.getInt("schema_version"),
                                                rs.getString("payload"),
                                                Instant.ofEpochMilli(rs.getLong("occurred_at")))),
                        q.tenantNo(),
                        cursor,
                        q.limit() + 1);
        boolean more = rows.size() > q.limit();
        var page = rows.subList(0, Math.min(rows.size(), q.limit()));
        return new EventPage(
                page.stream().map(Pending::event).toList(),
                page.isEmpty() ? q.afterCursor() : Long.toString(page.getLast().id()),
                more);
    }

    public void markPublished(String tenant, String eventNo, Instant publishedAt) {
        JdbcWriteGuard.require(ds, tenant);
        jdbc.update(
                "UPDATE commerce_outbox SET"
                        + " status='PUBLISHED',published_at=?,updated_at=?,version=version+1 WHERE"
                        + " tenant_no=? AND event_no=? AND status='PENDING'",
                publishedAt.toEpochMilli(),
                now(),
                tenant,
                eventNo);
    }
}
