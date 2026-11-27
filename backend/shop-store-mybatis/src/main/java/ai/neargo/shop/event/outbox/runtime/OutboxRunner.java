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
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
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
        if (stopped.get() || !started.compareAndSet(false, true)) { return; }
        recovery = scheduler.scheduleWithFixedDelay(this::recoverDue, recoveryInterval);
        recoverDue();
    }

    public void signal(String source) {
        if (started.get() && !stopped.get() && scanQueued.compareAndSet(false, true)) {
            try { scheduler.execute(() -> {
                scanQueued.set(false);
                recoverDue();
            }); }
            catch (RuntimeException ignored) { scanQueued.set(false); /* periodic recovery will retry */ }
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
                    Set<String> localHandlers = facade.localHandlerIds(store.source());
                    if (localHandlers.isEmpty()) { continue; }
                    store.recoverExpired(clock.instant(), batchSize, localHandlers);
                    for (DeliveryRef ref : store.findDue(clock.instant(), batchSize, localHandlers)) {
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
                var relay = facade.relay();
                DeliveryResult result;
                try {
                    result = handler != null && relay != null && relay.relays(c.event().eventType())
                            // 改发中间件：确认接收即算投出，由订阅端调回同一个处理者（ADR-067）
                            ? relayed(relay, c)
                            : handler == null ? DeliveryResult.failed("NO_HANDLER", "Handler not registered")
                            : OutboxLease.withCheck(() -> leaseValid.get()
                                && store.renew(c, clock.instant(), leaseDuration), () -> handler.handle(c.event()));
                } catch (ai.neargo.shop.event.outbox.OutboxSourceConflictException ex) {
                    result = DeliveryResult.failed("SOURCE_EVENT_CONFLICT", "Source identity has conflicting content");
                } catch (org.springframework.dao.DataAccessException ex) {
                    result = DeliveryResult.retry("TARGET_UNAVAILABLE", ex.getClass().getSimpleName());
                } catch (ai.neargo.shop.event.outbox.RetryableOutboxException ex) {
                    result = DeliveryResult.retry("CHANNEL_RETRYABLE", ex.getClass().getSimpleName());
                } catch (RuntimeException ex) {
                    result = handler != null && handler.externalWorkflow()
                            ? DeliveryResult.unknown(ex.getClass().getSimpleName())
                            : DeliveryResult.retry("HANDLER_FAILED", ex.getClass().getSimpleName());
                }
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

    private static DeliveryResult relayed(BrokerRelay relay, ClaimedDelivery c) {
        try {
            relay.publish(c.event(), c.handlerId());
            return DeliveryResult.sent();
        } catch (RuntimeException e) {
            // 中间件没确认：与目标不可用同等对待，按原退避重试（外部流程也不算“结果未知”，处理者根本没被调）
            return DeliveryResult.retry("BROKER_UNAVAILABLE", e.getClass().getSimpleName());
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
