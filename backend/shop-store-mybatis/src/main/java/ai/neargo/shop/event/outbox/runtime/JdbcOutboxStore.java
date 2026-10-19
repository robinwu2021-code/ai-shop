package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.outbox.ClaimedDelivery;
import ai.neargo.shop.event.outbox.DeliveryRef;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxStore;
import ai.neargo.shop.event.outbox.PublishReceipt;
import ai.neargo.shop.event.outbox.RetryPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Source-bound SQL adapter. Table names come exclusively from the fixed Source enum. */
public final class JdbcOutboxStore implements OutboxStore {
    public enum Source {
        PLATFORM("sys"), INVENTORY("inv");
        private final String prefix;
        Source(String prefix) { this.prefix = prefix; }
    }

    private final Source source;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final String events;
    private final String deliveries;
    private final String streams;
    private final String operations;

    public JdbcOutboxStore(Source source, DataSource dataSource, PlatformTransactionManager manager, ObjectMapper json) {
        this.source = source;
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.events = source.prefix + "_outbox";
        this.deliveries = source.prefix + "_outbox_delivery";
        this.streams = source.prefix + "_outbox_stream";
        this.operations = source.prefix + "_outbox_operation";
    }

    @Override
    public String source() { return source.name(); }

    /** Caller must already hold this source's business transaction. */
    public PublishReceipt append(OutboxEnvelope event, Map<String, Boolean> targets, boolean archiveOnly) {
        requireSourceTransaction();
        String fingerprint = fingerprint(event);
        try {
            jdbc.update("INSERT INTO " + streams + " (stream_key,next_sequence) VALUES (?,1)", event.streamKey());
        } catch (DuplicateKeyException existingStream) {
            // Dedicated primary-key insert: another transaction created this stream.
        }
        Long sequence = jdbc.queryForObject("SELECT next_sequence FROM " + streams
                + " WHERE stream_key=? FOR UPDATE", Long.class, event.streamKey());
        jdbc.update("UPDATE " + streams + " SET next_sequence=next_sequence+1 WHERE stream_key=?", event.streamKey());
        String state = archiveOnly ? "ARCHIVED" : targets.isEmpty() ? "FAILED" : "PENDING";
        String columns = "event_no,aggregate_type,aggregate_id,event_type,payload,status,retry_count,created_at,"
                + "source_system,source_event_no,schema_version,payload_hash,stream_key,stream_sequence,"
                + "routing_mode,occurred_at,last_error";
        List<Object> values = new ArrayList<>(List.of(event.eventNo(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.payload(), state, 0, at(Instant.now()), event.sourceSystem(),
                event.sourceEventNo(), event.schemaVersion(), fingerprint, event.streamKey(), sequence,
                archiveOnly ? "ARCHIVE_ONLY" : "DELIVERY", at(event.occurredAt())));
        values.add(!archiveOnly && targets.isEmpty() ? "NO_CONSUMER" : null);
        if (source == Source.INVENTORY) {
            columns += ",owner_id";
            values.add(event.aggregateId());
        }
        jdbc.update("INSERT INTO " + events + " (" + columns + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")", values.toArray());
        if (!archiveOnly) {
            targets.forEach((handler, external) -> {
                if (handler == null || handler.isBlank() || handler.length() > 128) {
                    throw new IllegalArgumentException("Invalid handler ID");
                }
                jdbc.update("INSERT INTO " + deliveries + " (event_no,handler_id,stream_key,stream_sequence,"
                                + "external_workflow,status,created_at) VALUES (?,?,?,?,?,'PENDING',?)",
                        event.eventNo(), handler, event.streamKey(), sequence, external ? 1 : 0, at(Instant.now()));
            });
        }
        return new PublishReceipt(event.eventNo(), true);
    }

    /** Source acknowledgement may be lost; receiving the same source event must return its original local ID. */
    public PublishReceipt accept(OutboxEnvelope event, Map<String, Boolean> targets) {
        String hash = fingerprint(event);
        try {
            return transactions.execute(s -> append(event, targets, false));
        } catch (DuplicateKeyException conflict) {
            var rows = jdbc.queryForList("SELECT event_no,payload_hash FROM " + events
                    + " WHERE source_system=? AND source_event_no=?", event.sourceSystem(), event.sourceEventNo());
            if (rows.isEmpty()) { throw conflict; }
            if (!hash.equals(rows.getFirst().get("payload_hash"))) {
                throw new IllegalStateException("SOURCE_EVENT_CONFLICT", conflict);
            }
            return new PublishReceipt((String) rows.getFirst().get("event_no"), false);
        }
    }

    @Override
    public List<DeliveryRef> findDue(Instant now, int limit) {
        return jdbc.query("SELECT d.id FROM " + deliveries + " d WHERE d.status='PENDING'"
                        + " AND (d.next_retry_at IS NULL OR d.next_retry_at<=?)" + headPredicate()
                        + " ORDER BY d.id LIMIT ?", (rs, n) -> new DeliveryRef(source(), rs.getLong(1)),
                at(now), bounded(limit));
    }

    private String headPredicate() {
        return " AND NOT EXISTS (SELECT 1 FROM " + deliveries + " p WHERE p.handler_id=d.handler_id"
                + " AND p.stream_key=d.stream_key AND p.stream_sequence<d.stream_sequence"
                + " AND p.status IN ('PENDING','PROCESSING','FAILED'))";
    }

    @Override
    public Optional<ClaimedDelivery> tryClaim(DeliveryRef ref, String token, Instant now, Duration lease) {
        checkSource(ref);
        if (token == null || token.isBlank() || token.length() > 64 || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("Invalid claim");
        }
        return transactions.execute(s -> {
            var rows = jdbc.queryForList("SELECT d.* FROM " + deliveries
                    + " d WHERE d.id=? AND d.status='PENDING'"
                    + " AND (d.next_retry_at IS NULL OR d.next_retry_at<=?)" + headPredicate() + " FOR UPDATE",
                    ref.id(), at(now));
            if (rows.isEmpty()) { return Optional.empty(); }
            int changed = jdbc.update("UPDATE " + deliveries + " SET status='PROCESSING',claim_token=?,lease_until=?,"
                            + "attempt_count=attempt_count+1,round_attempt_count=round_attempt_count+1,"
                            + "first_attempt_at=COALESCE(first_attempt_at,?),last_attempt_at=?"
                            + " WHERE id=? AND status='PENDING'", token, at(now.plus(lease)), at(now), at(now), ref.id());
            if (changed != 1) { return Optional.empty(); }
            var row = rows.getFirst();
            OutboxEnvelope event = jdbc.queryForObject("SELECT * FROM " + events + " WHERE event_no=?",
                    (rs, n) -> envelope(rs), row.get("event_no"));
            return Optional.of(new ClaimedDelivery(ref, event, (String) row.get("handler_id"), token,
                    ((Number) row.get("attempt_count")).intValue() + 1,
                    ((Number) row.get("round_attempt_count")).intValue() + 1, now.plus(lease)));
        });
    }

    @Override
    public boolean complete(ClaimedDelivery claim, DeliveryResult result, Instant now, RetryPolicy policy) {
        checkSource(claim.ref());
        return Boolean.TRUE.equals(transactions.execute(s -> {
            lockEvent(claim.event().eventNo());
            String status = result.kind() == DeliveryResult.Kind.SENT ? "SENT" : "FAILED";
            Instant retry = null;
            if (result.kind() == DeliveryResult.Kind.RETRYABLE) {
                var delay = policy.retryDelay(claim.roundAttemptCount());
                if (delay.isPresent()) { status = "PENDING"; retry = now.plus(delay.get()); }
            }
            int changed = jdbc.update("UPDATE " + deliveries + " SET status=?,next_retry_at=?,completed_at=?,"
                            + "last_error_code=?,last_error=?,claim_token=NULL,lease_until=NULL"
                            + " WHERE id=? AND status='PROCESSING' AND claim_token=? AND lease_until>?",
                    status, at(retry), "PENDING".equals(status) ? null : at(now), result.errorCode(),
                    truncate(result.detail(), 500), claim.ref().id(), claim.claimToken(), at(now));
            if (changed == 1) { aggregate(claim.event().eventNo(), now); }
            return changed == 1;
        }));
    }

    @Override
    public boolean renew(ClaimedDelivery claim, Instant now, Duration lease) {
        checkSource(claim.ref());
        return jdbc.update("UPDATE " + deliveries + " SET lease_until=?"
                        + " WHERE id=? AND status='PROCESSING' AND claim_token=? AND lease_until>?",
                at(now.plus(lease)), claim.ref().id(), claim.claimToken(), at(now)) == 1;
    }

    @Override
    public int recoverExpired(Instant now, int limit) {
        var rows = jdbc.queryForList("SELECT id,event_no FROM " + deliveries
                + " WHERE status='PROCESSING' AND lease_until<=? ORDER BY id LIMIT ?", at(now), bounded(limit));
        int recovered = 0;
        for (var row : rows) {
            recovered += transactions.execute(s -> {
                lockEvent((String) row.get("event_no"));
                int changed = jdbc.update("UPDATE " + deliveries
                                + " SET status=CASE WHEN external_workflow=1 OR round_attempt_count>=10"
                                + " THEN 'FAILED' ELSE 'PENDING' END,"
                                + "last_error_code=CASE WHEN external_workflow=1 THEN 'OUTCOME_UNKNOWN'"
                                + " ELSE 'LEASE_EXPIRED' END,last_error='Lease expired before acknowledgement',"
                                + "next_retry_at=NULL,claim_token=NULL,lease_until=NULL"
                                + " WHERE id=? AND status='PROCESSING' AND lease_until<=?", row.get("id"), at(now));
                if (changed == 1) { aggregate((String) row.get("event_no"), now); }
                return changed;
            });
        }
        return recovered;
    }

    private void lockEvent(String eventNo) {
        jdbc.queryForObject("SELECT event_no FROM " + events + " WHERE event_no=? FOR UPDATE", String.class, eventNo);
    }

    private void aggregate(String eventNo, Instant now) {
        var counts = jdbc.queryForList("SELECT status,COUNT(*) AS n FROM " + deliveries
                + " WHERE event_no=? GROUP BY status", eventNo);
        var states = counts.stream().map(r -> (String) r.get("status")).toList();
        String state = states.contains("FAILED") ? "FAILED"
                : states.contains("PENDING") || states.contains("PROCESSING") ? "PENDING"
                : states.contains("SKIPPED") ? "SKIPPED" : "SENT";
        Integer attempts = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_count),0) FROM " + deliveries
                + " WHERE event_no=?", Integer.class, eventNo);
        jdbc.update("UPDATE " + events + " SET status=?,retry_count=?,sent_at=?,next_retry_at=NULL WHERE event_no=?",
                state, attempts, "SENT".equals(state) ? at(now) : null, eventNo);
    }

    /** Audited source-local operation. Replaying a successful consumer is deliberately forbidden. */
    public void operate(String requestId, String eventNo, String handlerId, String actor, String reason,
                        boolean skip, Instant now) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || actor == null
                || actor.isBlank() || actor.length() > 64 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("Operation requires bounded request ID, actor and reason");
        }
        transactions.executeWithoutResult(s -> {
            // Locking the event serializes retries of this operation and aggregate updates.
            lockEvent(eventNo);
            var prior = jdbc.queryForList("SELECT * FROM " + operations + " WHERE request_id=?", requestId);
            if (!prior.isEmpty()) {
                var p = prior.getFirst();
                if (!eventNo.equals(p.get("event_no")) || !handlerId.equals(p.get("handler_id"))
                        || !(skip ? "SKIP" : "REPLAY").equals(p.get("operation"))) {
                    throw new IllegalStateException("OPERATION_REQUEST_CONFLICT");
                }
                return;
            }
            int changed = jdbc.update("UPDATE " + deliveries + " SET status=?,round_attempt_count=0,"
                            + "replay_count=replay_count+1,last_replayed_at=?,next_retry_at=NULL,"
                            + "claim_token=NULL,lease_until=NULL,completed_at=?"
                            + " WHERE event_no=? AND handler_id=? AND status='FAILED'",
                    skip ? "SKIPPED" : "PENDING", at(now), skip ? at(now) : null, eventNo, handlerId);
            if (changed != 1) { throw new IllegalStateException("Only FAILED deliveries can be replayed or skipped"); }
            jdbc.update("INSERT INTO " + operations + " (request_id,event_no,handler_id,operation,actor,reason,"
                            + "before_status,after_status,created_at) VALUES (?,?,?,?,?,?,'FAILED',?,?)",
                    requestId, eventNo, handlerId, skip ? "SKIP" : "REPLAY", actor, reason,
                    skip ? "SKIPPED" : "PENDING", at(now));
            aggregate(eventNo, now);
        });
    }

    /** Diagnostic results contain identities and progress, never event payloads. */
    public List<Map<String, Object>> diagnostics(int limit) {
        return jdbc.queryForList("SELECT event_no,handler_id,stream_key,stream_sequence,status,attempt_count,"
                + "round_attempt_count,replay_count,next_retry_at,lease_until,last_attempt_at,last_error_code,last_error"
                + " FROM " + deliveries + " ORDER BY last_attempt_at DESC,id DESC LIMIT ?", bounded(limit));
    }

    public Map<String, Long> counts() {
        Map<String, Long> result = new TreeMap<>();
        jdbc.query("SELECT status,COUNT(*) AS n FROM " + deliveries + " GROUP BY status", rs -> {
            result.put(rs.getString("status"), rs.getLong("n"));
        });
        return result;
    }

    /** Reject an unrelated active transaction: synchronization alone does not establish atomicity. */
    public void requireSourceTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Outbox publication requires source transaction: " + source());
        }
    }

    private String fingerprint(OutboxEnvelope e) {
        try {
            Object value = canonical(json.readValue(e.payload(), Object.class));
            String content = json.writeValueAsString(List.of(e.sourceSystem(), e.sourceEventNo(), e.eventType(),
                    e.schemaVersion(), e.aggregateType(), e.aggregateId(), e.streamKey(), e.occurredAt().toString(), value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid outbox JSON", ex);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, v) -> sorted.put(key.toString(), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) { return list.stream().map(this::canonical).toList(); }
        if (value instanceof Number n) { return new BigDecimal(n.toString()).stripTrailingZeros(); }
        return value;
    }

    private OutboxEnvelope envelope(ResultSet rs) throws SQLException {
        return new OutboxEnvelope(rs.getString("event_no"), rs.getString("source_system"),
                rs.getString("source_event_no"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("stream_key"),
                rs.getLong("stream_sequence"), rs.getString("payload"), rs.getTimestamp("occurred_at").toInstant());
    }
    private void checkSource(DeliveryRef ref) {
        if (!source().equals(ref.source())) { throw new IllegalArgumentException("Wrong delivery source"); }
    }
    private static int bounded(int limit) { return Math.max(1, Math.min(limit, 1000)); }
    private static Timestamp at(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static String truncate(String s, int max) { return s == null ? null : s.substring(0, Math.min(max, s.length())); }
}
