package ai.neargo.shop.merchantbridge;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opt-in in-process trigger for {@link EntityRegistrationProjectionCoordinator#run()} on deployments
 * without the {@code worker} profile (UME166 runs api/ops only, like {@code StoreSyncPublisher}).
 * Excluded under {@code worker}, where the {@code @Scheduled} path owns the job; both paths share
 * the ShedLock name, so instances never project concurrently.
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(
    name = {"shop.pay.entity-profiles.enabled", "shop.pay.entity-profiles.projection-in-process"},
    havingValue = "true")
public class EntityRegistrationProjectionTimer {
  private static final Logger log = LoggerFactory.getLogger(EntityRegistrationProjectionTimer.class);

  private final EntityRegistrationProjectionCoordinator coordinator;
  private final LockingTaskExecutor locks;
  private final long delayMillis;
  private ScheduledExecutorService timer;

  public EntityRegistrationProjectionTimer(
      EntityRegistrationProjectionCoordinator coordinator,
      LockingTaskExecutor locks,
      @Value("${shop.pay.entity-profiles.projection-delay:30000}") long delayMillis) {
    this.coordinator = coordinator;
    this.locks = locks;
    this.delayMillis = Math.max(5_000, delayMillis);
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, EntityRegistrationProjectionCoordinator.LOCK);
              t.setDaemon(true);
              return t;
            });
    timer.scheduleWithFixedDelay(
        () -> {
          try {
            tick();
          } catch (RuntimeException e) {
            log.warn("[entity-registration-projection] scan failed: {}", e.toString());
          }
        },
        delayMillis,
        delayMillis,
        TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stop() {
    if (timer != null) timer.shutdownNow();
  }

  /** Same lock bounds as the worker's {@code @SchedulerLock}. */
  public void tick() {
    locks.executeWithLock(
        (Runnable) coordinator::run,
        new LockConfiguration(
            Instant.now(),
            EntityRegistrationProjectionCoordinator.LOCK,
            Duration.ofMinutes(10),
            Duration.ofSeconds(5)));
  }
}
