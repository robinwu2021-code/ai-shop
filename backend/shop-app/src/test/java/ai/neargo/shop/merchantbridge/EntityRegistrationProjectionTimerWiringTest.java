package ai.neargo.shop.merchantbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The in-process timer and the worker {@code @Scheduled} path never both fire in one process. */
class EntityRegistrationProjectionTimerWiringTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(
              EntityRegistrationProjectionCoordinator.class,
              () -> mock(EntityRegistrationProjectionCoordinator.class))
          .withBean(LockingTaskExecutor.class, () -> mock(LockingTaskExecutor.class))
          .withUserConfiguration(EntityRegistrationProjectionTimer.class);

  @Test
  void optInStartsTheTimerOnApiOps() {
    runner
        .withPropertyValues(
            "spring.profiles.active=api,ops",
            "shop.pay.entity-profiles.enabled=true",
            "shop.pay.entity-profiles.projection-in-process=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(EntityRegistrationProjectionTimer.class));
  }

  @Test
  void offByDefault() {
    runner
        .withPropertyValues("shop.pay.entity-profiles.enabled=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(EntityRegistrationProjectionTimer.class));
  }

  @Test
  void workerKeepsOnlyTheScheduledPath() {
    runner
        .withPropertyValues(
            "spring.profiles.active=worker",
            "shop.pay.entity-profiles.enabled=true",
            "shop.pay.entity-profiles.projection-in-process=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(EntityRegistrationProjectionTimer.class));
  }

  @Test
  void needsTheProjectionItself() {
    runner
        .withPropertyValues("shop.pay.entity-profiles.projection-in-process=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(EntityRegistrationProjectionTimer.class));
  }
}
