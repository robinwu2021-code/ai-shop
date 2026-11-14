package ai.neargo.shop.paybridge;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Sends explicitly synthetic samples through the real authenticated SSE writer, then stops. */
public final class PaymentCollectionDiagnosticJob implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PaymentCollectionDiagnosticJob.class);
    private final PaymentCollectionStream stream;
    private final PaymentCollectionDiagnosticProperties properties;
    private final Clock clock;
    private final String runId = UUID.randomUUID().toString();
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private long startedAt, sequence;

    public PaymentCollectionDiagnosticJob(PaymentCollectionStream stream,
            PaymentCollectionDiagnosticProperties properties, Clock clock) {
        this(stream, properties, clock, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "payment-sse-diagnostic");
            thread.setDaemon(true);
            return thread;
        }));
    }
    PaymentCollectionDiagnosticJob(PaymentCollectionStream stream,
            PaymentCollectionDiagnosticProperties properties, Clock clock,
            ScheduledExecutorService scheduler) {
        this.stream = stream;
        this.properties = properties;
        this.clock = clock;
        this.scheduler = scheduler;
    }
    @Override public synchronized void start() {
        if (running) return;
        if (!properties.isValidConfiguration()) throw new IllegalArgumentException("Invalid SSE diagnostic configuration");
        startedAt = clock.millis();
        running = true;
        scheduler.scheduleAtFixedRate(this::tick, properties.interval(), properties.interval(), TimeUnit.MILLISECONDS);
        log.info("payment SSE diagnostic started run={} intervalMs={} durationMs={}",
                runId, properties.interval(), properties.duration());
    }
    synchronized void tick() {
        if (!running) return;
        long now = clock.millis();
        if (now - startedAt > properties.duration()) { stop(); return; }
        try {
            int queued = stream.diagnostic(properties, runId, ++sequence, now);
            log.info("payment SSE diagnostic generated run={} seq={} queued={}", runId, sequence, queued);
        } catch (RuntimeException failure) {
            log.warn("payment SSE diagnostic failed run={} seq={} cause={}",
                    runId, sequence, failure.getClass().getSimpleName());
        }
        if (now - startedAt >= properties.duration()
                || sequence >= properties.duration() / properties.interval()) stop();
    }
    @Override public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdownNow();
        log.info("payment SSE diagnostic stopped run={} generated={}", runId, sequence);
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
