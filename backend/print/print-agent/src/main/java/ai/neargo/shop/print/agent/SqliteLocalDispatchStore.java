package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class SqliteLocalDispatchStore implements LocalDispatchStore {
    private final String url;
    private final AgentJson json = new AgentJson();

    public SqliteLocalDispatchStore(Path file) {
        try {
            var parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var c = open();
                var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS local_dispatch(dispatch_key TEXT PRIMARY KEY,"
                            + " status TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT"
                            + " NULL)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS local_event(event_no TEXT PRIMARY KEY, dispatch_key"
                            + " TEXT NOT NULL, sequence_no INTEGER NOT NULL, payload TEXT NOT NULL,"
                            + " acknowledged INTEGER NOT NULL DEFAULT 0,"
                            + " UNIQUE(dispatch_key,sequence_no))");
        } catch (SQLException e) {
            throw new IllegalStateException("AGENT_STORAGE_UNAVAILABLE", e);
        }
    }

    private Connection open() throws SQLException {
        var c = DriverManager.getConnection(url);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
        }
        return c;
    }

    private <T> T transaction(java.util.function.Function<Connection, T> body) {
        try (var c = open()) {
            c.setAutoCommit(false);
            try {
                T result = body.apply(c);
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("AGENT_STORAGE_FAILED", e);
        }
    }

    private Optional<LocalDispatch> find(Connection c, String key) {
        try (var s =
                c.prepareStatement("SELECT payload FROM local_dispatch WHERE dispatch_key=?")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) {
                return r.next()
                        ? Optional.of(json.read(r.getString(1), LocalDispatch.class))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Optional<LocalDispatch> find(String key) {
        try (var c = open()) {
            return find(c, key);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDispatch updated(
            LocalDispatch d,
            ObservedPrintStatus status,
            String lease,
            Instant until,
            String systemId,
            Instant submitting,
            Instant finished) {
        return new LocalDispatch(
                d.dispatchKey(),
                d.jobNo(),
                d.attemptNo(),
                d.printerNo(),
                d.sessionEpoch(),
                d.fenceEpoch(),
                d.connectionRevision(),
                lease,
                until,
                until,
                d.layout(),
                d.documentHash(),
                d.connection(),
                status,
                systemId,
                d.revision() + 1,
                d.receivedAt(),
                submitting,
                finished);
    }

    private void save(Connection c, LocalDispatch old, LocalDispatch next) {
        try (var s =
                c.prepareStatement(
                        "UPDATE local_dispatch SET status=?,revision=?,payload=? WHERE"
                                + " dispatch_key=? AND revision=?")) {
            s.setString(1, next.status().name());
            s.setLong(2, next.revision());
            s.setString(3, json.write(next));
            s.setString(4, old.dispatchKey());
            s.setLong(5, old.revision());
            if (s.executeUpdate() != 1) throw new IllegalStateException("AGENT_REVISION_CONFLICT");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void append(Connection c, LocalEvent e) {
        try (var s =
                c.prepareStatement(
                        "INSERT INTO local_event(event_no,dispatch_key,sequence_no,payload)"
                                + " VALUES(?,?,?,?)")) {
            s.setString(1, e.eventNo());
            s.setString(2, e.dispatchKey());
            s.setLong(3, e.sequence());
            s.setString(4, json.write(e));
            s.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public synchronized void receiveAndCommit(ClaimDispatch d) {
        transaction(
                c -> {
                    var old = find(c, d.dispatchKey());
                    if (old.isPresent()) {
                        var o = old.get();
                        if (!o.attemptNo().equals(d.attemptNo())
                                || o.fenceEpoch() != d.fenceEpoch()
                                || !o.documentHash().equals(d.documentHash())
                                || !o.layout().contentHash().equals(d.layout().contentHash()))
                            throw new IllegalStateException("AGENT_DISPATCH_CONFLICT");
                        return null;
                    }
                    if (!json.freeze(d.layout().pages()).sha256().equals(d.layout().contentHash()))
                        throw new IllegalStateException("AGENT_LAYOUT_HASH_MISMATCH");
                    var row =
                            new LocalDispatch(
                                    d.dispatchKey(),
                                    d.jobNo(),
                                    d.attemptNo(),
                                    d.printerNo(),
                                    d.sessionEpoch(),
                                    d.fenceEpoch(),
                                    d.connectionRevision(),
                                    d.leaseToken(),
                                    d.leaseUntil(),
                                    d.executeBefore(),
                                    d.layout(),
                                    d.documentHash(),
                                    d.connection(),
                                    ObservedPrintStatus.RECEIVED,
                                    null,
                                    0,
                                    Instant.now(),
                                    null,
                                    null);
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " local_dispatch(dispatch_key,status,revision,payload)"
                                        + " VALUES(?,?,?,?)")) {
                        s.setString(1, d.dispatchKey());
                        s.setString(2, "RECEIVED");
                        s.setLong(3, 0);
                        s.setString(4, json.write(row));
                        s.executeUpdate();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    return null;
                });
    }

    public synchronized void applyLeaseGrant(
            String key, long revision, String token, Instant until) {
        transaction(
                c -> {
                    var d = find(c, key).orElseThrow();
                    if (d.revision() != revision)
                        throw new IllegalStateException("AGENT_REVISION_CONFLICT");
                    save(
                            c,
                            d,
                            updated(
                                    d,
                                    d.status(),
                                    token,
                                    until,
                                    d.systemJobId(),
                                    d.submittingAt(),
                                    d.finishedAt()));
                    return null;
                });
    }

    public synchronized void markSubmittingAndAppendEvent(String key, long revision, LocalEvent e) {
        transaction(
                c -> {
                    var d = find(c, key).orElseThrow();
                    if (d.revision() != revision
                            || !Set.of(ObservedPrintStatus.RECEIVED, ObservedPrintStatus.ACCEPTED)
                                    .contains(d.status())
                            || !d.leaseUntil().isAfter(Instant.now())
                            || e.status() != ObservedPrintStatus.SUBMITTING)
                        throw new IllegalStateException("AGENT_NOT_EXECUTABLE");
                    save(
                            c,
                            d,
                            updated(
                                    d,
                                    ObservedPrintStatus.SUBMITTING,
                                    d.leaseToken(),
                                    d.leaseUntil(),
                                    d.systemJobId(),
                                    Instant.now(),
                                    null));
                    append(c, e);
                    return null;
                });
    }

    public synchronized boolean cancelBeforeSubmittingAndAppendEvent(
            String key, long expectedRevision, LocalEvent e) {
        return transaction(
                c -> {
                    var d = find(c, key).orElseThrow();
                    if (d.revision() != expectedRevision
                            || d.submittingAt() != null
                            || !Set.of(ObservedPrintStatus.RECEIVED, ObservedPrintStatus.ACCEPTED)
                                    .contains(d.status())) return false;
                    if (e.status() != ObservedPrintStatus.CANCELLED
                            || !e.dispatchKey().equals(key)
                            || !e.attemptNo().equals(d.attemptNo())
                            || e.fenceEpoch() != d.fenceEpoch())
                        throw new IllegalStateException("AGENT_EVENT_CONFLICT");
                    save(
                            c,
                            d,
                            updated(
                                    d,
                                    ObservedPrintStatus.CANCELLED,
                                    d.leaseToken(),
                                    d.leaseUntil(),
                                    d.systemJobId(),
                                    null,
                                    Instant.now()));
                    append(c, e);
                    return true;
                });
    }

    public synchronized void recordObservationAndAppendEvent(
            String key, QueueObservation observation, LocalEvent e) {
        transaction(
                c -> {
                    var d = find(c, key).orElseThrow();
                    if (!e.dispatchKey().equals(key)
                            || !e.attemptNo().equals(d.attemptNo())
                            || e.fenceEpoch() != d.fenceEpoch())
                        throw new IllegalStateException("AGENT_EVENT_CONFLICT");
                    // A stale desktop observation is never proof that an already submitted queue
                    // terminated.
                    if (observation.status() == ObservedPrintStatus.CANCELLED)
                        throw new IllegalStateException("USE_ATOMIC_PRE_SUBMIT_CANCELLATION");
                    Instant finished =
                            Set.of(
                                                    ObservedPrintStatus.CONFIRMED,
                                                    ObservedPrintStatus.CANCELLED,
                                                    ObservedPrintStatus.NOT_ACCEPTED,
                                                    ObservedPrintStatus.FAILED)
                                            .contains(observation.status())
                                    ? Instant.now()
                                    : null;
                    save(
                            c,
                            d,
                            updated(
                                    d,
                                    observation.status(),
                                    d.leaseToken(),
                                    d.leaseUntil(),
                                    observation.systemJobId(),
                                    d.submittingAt(),
                                    finished));
                    append(c, e);
                    return null;
                });
    }

    public synchronized List<LocalDispatch> unresolved(int limit) {
        try (var c = open();
                var s =
                        c.prepareStatement(
                                "SELECT payload FROM local_dispatch WHERE status NOT IN"
                                    + " ('CONFIRMED','CANCELLED','NOT_ACCEPTED','FAILED') ORDER BY"
                                    + " rowid LIMIT ?")) {
            s.setInt(1, Math.min(100, Math.max(1, limit)));
            try (var r = s.executeQuery()) {
                var out = new ArrayList<LocalDispatch>();
                while (r.next()) out.add(json.read(r.getString(1), LocalDispatch.class));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized List<LocalEvent> pendingEvents(int limit) {
        try (var c = open();
                var s =
                        c.prepareStatement(
                                "SELECT payload FROM local_event WHERE acknowledged=0 ORDER BY"
                                        + " rowid LIMIT ?")) {
            s.setInt(1, Math.min(100, Math.max(1, limit)));
            try (var r = s.executeQuery()) {
                var out = new ArrayList<LocalEvent>();
                while (r.next()) out.add(json.read(r.getString(1), LocalEvent.class));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void markEventsAcknowledged(Set<String> ids) {
        transaction(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE local_event SET acknowledged=1 WHERE event_no=?")) {
                        for (var id : ids) {
                            s.setString(1, id);
                            s.addBatch();
                        }
                        s.executeBatch();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    return null;
                });
    }

    public synchronized long nextSequence(String key) {
        try (var c = open();
                var s =
                        c.prepareStatement(
                                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM local_event WHERE"
                                        + " dispatch_key=?")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("AGENT_SEQUENCE_UNAVAILABLE");
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
