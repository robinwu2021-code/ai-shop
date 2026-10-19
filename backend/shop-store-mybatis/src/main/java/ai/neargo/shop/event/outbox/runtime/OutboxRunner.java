package ai.neargo.shop.event.outbox.runtime;

import ai.neargo.shop.event.outbox.ClaimedDelivery;
import ai.neargo.shop.event.outbox.DeliveryRef;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.RetryPolicy;
import ai.neargo.shop.event.outbox.OutboxLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The database is the durable queue; memory holds only bounded acceleration hints. */
public final class OutboxRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OutboxRunner.class);
    private final UnifiedOutboxFacade facade;
    private final Clock clock;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskScheduler scheduler;
    private final Set<DeliveryRef> queued = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicInteger firstSource = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private ScheduledFuture<?> recovery;
    private final Duration recoveryInterval;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final int batchSize;
    private final RetryPolicy retryPolicy;

    public OutboxRunner(UnifiedOutboxFacade facade, Clock clock, int concurrency, int queueCapacity) {
        this(facade, clock, defaults(concurrency, queueCapacity));
    }
    private static OutboxProperties defaults(int concurrency, int queueCapacity) {
        OutboxProperties props = new OutboxProperties();
        props.setConcurrency(concurrency); props.setQueueCapacity(queueCapacity);
        return props;
    }
    public OutboxRunner(UnifiedOutboxFacade facade, Clock clock, OutboxProperties props) {
        props.validate();
        this.facade = facade; this.clock = clock;
        this.recoveryInterval = props.getRecoveryInterval();
        this.leaseDuration = props.getLeaseDuration(); this.heartbeatInterval = props.getHeartbeatInterval();
        this.batchSize = props.getBatchSize(); this.retryPolicy = props.retryPolicy();
        int concurrency = props.getConcurrency(); int queueCapacity = props.getQueueCapacity();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency); executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity); executor.setThreadNamePrefix("outbox-unified-");
        executor.setWaitForTasksToCompleteOnShutdown(false); executor.initialize();
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("outbox-timer-"); scheduler.initialize();
        facade.setWakeup(source -> signal(source));
    }

    /** Explicit lifecycle scheduling works without worker profile or the business job switch. */
    public void start() {
        recovery = scheduler.scheduleWithFixedDelay(this::recoverDue, recoveryInterval);
        recoverDue();
    }

    public void signal(String source) {
        if (!stopped.get()) {
            try { scheduler.execute(this::recoverDue); }
            catch (RuntimeException ignored) { /* periodic recovery will retry */ }
        }
    }

    public void recoverDue() {
        if (stopped.get() || !scanning.compareAndSet(false, true)) { return; }
        try {
            var stores = facade.stores().stream().sorted(java.util.Comparator.comparing(JdbcOutboxStore::source)).toList();
            if (stores.isEmpty()) { return; }
            int start = Math.floorMod(firstSource.getAndIncrement(), stores.size());
            for (int i = 0; i < stores.size(); i++) {
                var store = stores.get((i + start) % stores.size());
                try {
                    store.recoverExpired(clock.instant(), batchSize);
                    for (DeliveryRef ref : store.findDue(clock.instant(), batchSize)) {
                        if (!queued.add(ref)) { continue; }
                        try { executor.execute(() -> runOne(ref)); }
                        catch (RuntimeException rejected) { queued.remove(ref); break; }
                    }
                } catch (RuntimeException ex) {
                    log.warn("outbox recovery failed source={} error={}", store.source(), ex.getClass().getSimpleName());
                }
            }
        } finally { scanning.set(false); }
    }

    private void runOne(DeliveryRef ref) {
        try {
            if (stopped.get()) { return; }
            var store = facade.store(ref.source());
            var claim = store.tryClaim(ref, UUID.randomUUID().toString(), clock.instant(), leaseDuration);
            if (claim.isEmpty()) { return; }
            ClaimedDelivery c = claim.get();
            AtomicBoolean leaseValid = new AtomicBoolean(true);
            ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (!store.renew(c, clock.instant(), leaseDuration)) { leaseValid.set(false); }
                } catch (RuntimeException ex) { leaseValid.set(false); }
            }, clock.instant().plus(heartbeatInterval), heartbeatInterval);
            try {
                var handler = facade.handler(ref.source(), c.handlerId());
                DeliveryResult result = handler == null ? DeliveryResult.failed("NO_HANDLER", "Handler not registered")
                         : OutboxLease.withCheck(() -> leaseValid.get()
                                && store.renew(c, clock.instant(), leaseDuration),
                                () -> handler.handle(c.event()));
                if (leaseValid.get() && store.complete(c, result, clock.instant(), retryPolicy)) {
                    if (result.kind() == DeliveryResult.Kind.RETRYABLE) {
                        retryPolicy.retryDelay(c.roundAttemptCount()).ifPresent(delay ->
                                scheduler.schedule(() -> signal(ref.source()), clock.instant().plus(delay)));
                    }
                }
            } finally { heartbeat.cancel(false); }
        } catch (RuntimeException ex) {
            log.warn("outbox execution failed source={} delivery={} error={}",
                    ref.source(), ref.id(), ex.getClass().getSimpleName());
        } finally {
            queued.remove(ref);
            signal(ref.source());
        }
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queued.isEmpty() && !scanning.get() && executor.getActiveCount() == 0) { return true; }
            Thread.sleep(10);
        }
        return false;
    }
    @Override
    public void close() {
        stopped.set(true);
        if (recovery != null) { recovery.cancel(false); }
        executor.shutdown(); scheduler.shutdown();
    }
}
