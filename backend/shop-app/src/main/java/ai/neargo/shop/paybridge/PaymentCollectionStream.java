package ai.neargo.shop.paybridge;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Shared projections survive instance changes; bounded independent writers isolate slow sockets.
 */
public final class PaymentCollectionStream implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PaymentCollectionStream.class);
    private final JdbcTemplate jdbc;
    private final PaymentCollectionProperties properties;
    private final PaymentCollectionSecurity security;
    private final BizIdentityResolver identities;
    private final TokenStore tokens;
    private final PaymentCollectionProjection projection;
    private final Clock clock;
    private final Supplier<SseEmitter> emitters;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    new CustomizableThreadFactory("payment-sse-"));
    private long lastCleanup;

    public PaymentCollectionStream(
            DataSource ds,
            PaymentCollectionProperties properties,
            PaymentCollectionSecurity security,
            BizIdentityResolver identities,
            TokenStore tokens,
            PaymentCollectionProjection projection,
            Clock clock) {
        this(
                ds,
                properties,
                security,
                identities,
                tokens,
                projection,
                clock,
                () -> new SseEmitter(0L));
    }

    public PaymentCollectionStream(
            DataSource ds,
            PaymentCollectionProperties properties,
            PaymentCollectionSecurity security,
            BizIdentityResolver identities,
            TokenStore tokens,
            PaymentCollectionProjection projection,
            Clock clock,
            Supplier<SseEmitter> emitters) {
        jdbc = new JdbcTemplate(ds);
        this.properties = properties;
        this.security = security;
        this.identities = identities;
        this.tokens = tokens;
        this.projection = projection;
        this.clock = clock;
        this.emitters = emitters;
        scheduler.scheduleWithFixedDelay(
                this::tick, properties.getPollMs(), properties.getPollMs(), TimeUnit.MILLISECONDS);
    }

    public SseEmitter open(LoginUser user, BizContext ctx, String bearer, String lastId) {
        return open(user, ctx, bearer, lastId, () -> true, () -> {});
    }

    public SseEmitter open(
            LoginUser user,
            BizContext ctx,
            String bearer,
            String lastId,
            BooleanSupplier committed,
            Runnable abortHttp) {
        String store = ctx.currentStoreNo(), entity = ctx.merchantNo();
        if (!ctx.hasStoreAccess(store) || !ctx.can(BizPerms.PAY_ORDER_VIEW))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String scope = PaymentCollectionProjection.scope(user, entity, store);
        long serverNow = clock.millis();
        long[] bounds = bounds(scope);
        long start;
        try {
            start = lastId == null ? bounds[1] : security.sequence(lastId, scope);
        } catch (ResponseStatusException invalid) {
            if (invalid.getStatusCode() != HttpStatus.CONFLICT) throw invalid;
            return reset();
        }
        if (start < bounds[0] || start > bounds[1]) return reset();
        SseEmitter emitter = emitters.get();
        Connection c =
                new Connection(
                        emitter, user, bearer, entity, store, scope, start, bounds[1], committed,
                        abortHttp);
        emitter.onCompletion(c::dispose);
        emitter.onTimeout(c::dispose);
        emitter.onError(error -> {
            log.info("payment SSE transport error connection={} cause={}", c.writer.threadId(),
                    error.getClass().getSimpleName());
            c.dispose();
        });
        c.enqueue(
                new Frame(
                        "ready",
                        Map.of(
                                "schemaVersion",
                                1,
                                "serverNow",
                                serverNow,
                                "storeNo",
                                store,
                                "resume",
                                lastId == null ? "live" : "replay"),
                        security.cursor(scope, start),
                        false));
        connections.add(c);
        c.writer.start();
        return emitter;
    }

    private SseEmitter reset() {
        SseEmitter emitter = emitters.get();
        // Before Spring initializes this emitter, this single bounded control frame is buffered.
        try {
            emitter.send(
                    SseEmitter.event().name("resync_required").data(Map.of("schemaVersion", 1)));
            emitter.complete();
        } catch (IOException disconnected) {
            emitter.completeWithError(disconnected);
        }
        return emitter;
    }

    private long[] bounds(String scope) {
        var rows =
                jdbc.queryForList(
                        "SELECT floor_seq,seq FROM payment_collection_scope WHERE scope_key=?",
                        scope);
        return rows.isEmpty()
                ? new long[] {0, 0}
                : new long[] {
                    ((Number) rows.getFirst().get("floor_seq")).longValue(),
                    ((Number) rows.getFirst().get("seq")).longValue()
                };
    }

    private void tick() {
        try {
            var scopes = new HashMap<String, List<Connection>>();
            for (Connection c : connections) {
                if (c.withinWriteDeadline() && !c.closing.get() && c.authorized())
                    scopes.computeIfAbsent(c.scope, ignored -> new ArrayList<>()).add(c);
            }
            for (var entry : scopes.entrySet()) {
                long floor = bounds(entry.getKey())[0];
                long after = entry.getValue().stream().mapToLong(c -> c.sequence).min().orElse(0);
                var rows =
                        jdbc.queryForList(
                                "SELECT * FROM payment_collection_stream WHERE scope_key=? AND"
                                        + " seq>? ORDER BY seq LIMIT 100",
                                entry.getKey(),
                                after);
                for (Connection c : entry.getValue()) c.deliver(rows, floor);
            }
            if (clock.millis() - lastCleanup > 60000) {
                projection.cleanup(clock.millis() - properties.getRetentionMs());
                lastCleanup = clock.millis();
            }
        } catch (RuntimeException unavailable) {
            // This single task boundary fails streams closed; reconnect recovers committed facts.
            log.warn(
                    "payment SSE storage/authorization unavailable: {}",
                    unavailable.getClass().getSimpleName());
            for (Connection c : connections) c.abort(unavailable);
        }
    }

    private record Frame(String name, Object data, String id, boolean completeAfter) {}

    /** Diagnostic frames share the real writer but have neither a payment cursor nor financial side effects. */
    public int diagnostic(PaymentCollectionDiagnosticProperties target, String runId, long sequence, long sentAt) {
        if (!properties.isEnabled() || !"SANDBOX".equals(properties.getEnvironment()))
            throw new IllegalStateException("SSE diagnostics require SANDBOX collections");
        int queued = 0;
        for (Connection c : connections) {
            if (!target.userNo().equals(c.user.userNo()) || !target.entityNo().equals(c.entity)
                    || !target.storeNo().equals(c.store) || c.closing.get()
                    || !c.withinWriteDeadline() || !c.authorized()) continue;
            if (c.enqueue(new Frame("diagnostic.ping", Map.of(
                    "schemaVersion", 1, "diagnostic", true, "runId", runId,
                    "sequence", sequence, "sentAt", sentAt,
                    "storeNo", c.store, "entityNo", c.entity), null, false))) queued++;
        }
        return queued;
    }

    private final class Connection {
        final SseEmitter emitter;
        final LoginUser user;
        final String bearer, entity, store, scope;
        final long replayTip;
        final BooleanSupplier committed;
        final Runnable abortHttp;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean closing = new AtomicBoolean();
        final ArrayBlockingQueue<Frame> pending =
                new ArrayBlockingQueue<>(properties.getSendQueueCapacity());
        final Thread writer;
        volatile long writingSince;
        long sequence, lastHeartbeat = clock.millis();

        Connection(
                SseEmitter emitter,
                LoginUser user,
                String bearer,
                String entity,
                String store,
                String scope,
                long sequence,
                long replayTip,
                BooleanSupplier committed,
                Runnable abortHttp) {
            this.emitter = emitter;
            this.user = user;
            this.bearer = bearer;
            this.entity = entity;
            this.store = store;
            this.scope = scope;
            this.sequence = sequence;
            this.replayTip = replayTip;
            this.committed = committed;
            this.abortHttp = abortHttp;
            writer = Thread.ofVirtual().name("payment-sse-writer").unstarted(this::write);
        }

        boolean enqueue(Frame frame) {
            if (closed.get()) return false;
            if (pending.offer(frame)) return true;
            abort(new IOException("payment SSE send queue full"));
            return false;
        }

        boolean withinWriteDeadline() {
            if (closed.get()) return false;
            long started = writingSince;
            if (started != 0
                    && System.nanoTime() - started
                            > TimeUnit.MILLISECONDS.toNanos(properties.getWriteTimeoutMs())) {
                abort(new IOException("payment SSE write deadline exceeded"));
                return false;
            }
            return true;
        }

        boolean authorized() {
            var session = tokens.get(bearer);
            var ctx =
                    session.isEmpty()
                            ? BizContext.NONE
                            : identities.resolveStrict(session.get().user(), store);
            if (session.isPresent()
                    && session.get().user().equals(user)
                    && entity.equals(ctx.merchantNo())
                    && ctx.hasStoreAccess(store)
                    && ctx.withStore(store).can(BizPerms.PAY_ORDER_VIEW)) return true;
            finish("access_revoked");
            return false;
        }

        void finish(String name) {
            if (closing.compareAndSet(false, true)) {
                pending.clear();
                enqueue(new Frame(name, Map.of("schemaVersion", 1), null, true));
            }
        }

        void deliver(List<Map<String, Object>> rows, long floor) {
            if (closed.get() || closing.get()) return;
            if (sequence < floor) {
                finish("resync_required");
                return;
            }
            int budget = Math.max(0, pending.remainingCapacity() - 1);
            for (var row : rows) {
                long seq = ((Number) row.get("seq")).longValue();
                if (seq <= sequence) continue;
                if (budget-- <= 0) break;
                enqueue(
                        new Frame(
                                "payment.succeeded",
                                Map.of(
                                        "schemaVersion",
                                        1,
                                        "eventId",
                                        row.get("event_id"),
                                        "messageNo",
                                        row.get("message_no"),
                                        "payOrderNo",
                                        row.get("pay_order_no"),
                                        "storeNo",
                                        row.get("store_no"),
                                        "paidAt",
                                        row.get("paid_at"),
                                        "delivery",
                                        seq <= replayTip ? "replay" : "live"),
                                security.cursor(scope, seq),
                                false));
                if (closed.get()) return;
                sequence = seq;
            }
            if (clock.millis() - lastHeartbeat >= properties.getHeartbeatMs()) {
                enqueue(new Frame(null, null, null, false));
                lastHeartbeat = clock.millis();
            }
        }

        void write() {
            boolean initial = true;
            try {
                while (!closed.get()) {
                    Frame frame = pending.take();
                    if (closing.get() && !frame.completeAfter()) continue;
                    writingSince = System.nanoTime();
                    // Only ready may use Spring's pre-initialization buffer. Every later frame
                    // waits
                    // for the initial flush and stays within this connection's bounded queue.
                    while (!initial && !committed.getAsBoolean() && !closed.get()) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException();
                    }
                    if (closed.get()) break;
                    var builder =
                            frame.name() == null
                                    ? SseEmitter.event().comment("heartbeat")
                                    : SseEmitter.event().name(frame.name()).data(frame.data());
                    if (frame.id() != null) builder.id(frame.id());
                    emitter.send(builder);
                    if ("diagnostic.ping".equals(frame.name()) && frame.data() instanceof Map<?, ?> sample)
                        log.info("payment SSE diagnostic sent run={} seq={} connection={}",
                                sample.get("runId"), sample.get("sequence"), writer.threadId());
                    initial = false;
                    if (frame.completeAfter()) {
                        emitter.complete();
                        writingSince = 0;
                        break;
                    }
                    writingSince = 0;
                }
            } catch (IOException | IllegalStateException disconnected) {
                abort(disconnected);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                dispose();
            }
        }

        void dispose() {
            if (stop()) log.info("payment SSE completed connection={}", writer.threadId());
        }

        boolean stop() {
            if (!closed.compareAndSet(false, true)) return false;
            connections.remove(this);
            pending.clear();
            writer.interrupt();
            return true;
        }

        void abort(Throwable reason) {
            if (!stop()) return;
            log.info("payment SSE disconnected connection={} cause={}", writer.threadId(),
                    reason.getClass().getSimpleName());
            // Spring completion takes its send lock too. It must never run on the shared poller.
            Thread.ofVirtual()
                    .name("payment-sse-abort")
                    .start(
                            () -> {
                                try {
                                    abortHttp.run();
                                } catch (IllegalStateException alreadyClosed) {
                                    /* HTTP async context has completed. */
                                } finally {
                                    try {
                                        emitter.completeWithError(reason);
                                    } catch (IllegalStateException alreadyCompleting) {
                                        // stop() has closed this connection. The HTTP abort may
                                        // already have moved the servlet async state to COMPLETING.
                                    }
                                }
                            });
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        for (Connection c : connections) c.abort(new IOException("payment SSE service closed"));
    }
}
