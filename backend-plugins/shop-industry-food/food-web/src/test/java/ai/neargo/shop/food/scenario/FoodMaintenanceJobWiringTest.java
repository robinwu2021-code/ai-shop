package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.service.FoodMaintenanceService.Task;
import ai.neargo.shop.job.JobHandlerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C1：四个 {@code @Scheduled} 在生产上从未运行（{@code @EnableScheduling} 只挂在 worker profile，
 * 生产是 api,ops）。它们必须作为平台任务（JobHandler + JobDeclaration）出现在 api/ops 宿主里，
 * 由外部调度器经 {@code /internal/job/{name}/run} 触发。
 */
class FoodMaintenanceJobWiringTest {

    private static final Map<String, Task> JOBS = Map.of(
            "food-scheduled-dispatch", Task.SCHEDULED_DISPATCH,
            "food-queue-notice", Task.QUEUE_NOTICE,
            "food-production-recovery", Task.PRODUCTION_RECOVERY,
            "food-return-amendment-recovery", Task.RETURN_AMENDMENT_RECOVERY);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(Host.class);

    @Test
    void embeddedApiOpsHostExposesAllFourFoodJobsWithDeclarations() {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=embedded",
                        "shop.job.enabled=true", "spring.profiles.active=api,ops")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Map<String, JobHandler> handlers = handlers(context);
                    assertThat(handlers).containsKeys(JOBS.keySet().toArray(String[]::new));
                    Map<String, JobDeclaration> declarations = context.getBeansOfType(JobDeclaration.class)
                            .values().stream()
                            .collect(Collectors.toMap(JobDeclaration::handlerName, Function.identity()));
                    assertThat(declarations).containsKeys(JOBS.keySet().toArray(String[]::new));
                    JOBS.keySet().forEach(name -> assertThat(declarations.get(name).displayName())
                            .as("displayName 给运营看，不能是 handler 名").isNotEqualTo(name));
                    // 与平台注册表同一判据：无重名、每个声明都有实现
                    var registry = new JobHandlerRegistry(new ArrayList<>(handlers.values()),
                            new ArrayList<>(declarations.values()));
                    assertThat(registry.declarations()).hasSizeGreaterThanOrEqualTo(JOBS.size());

                    Recorder recorder = context.getBean(Recorder.class);
                    for (var job : JOBS.entrySet()) {
                        var result = handlers.get(job.getKey()).run(null);
                        assertThat(result.status()).as(job.getKey()).isEqualTo(JobStatus.SUCCESS);
                        assertThat(recorder.calls.getLast()).isEqualTo(job.getValue());
                    }
                    assertThat(recorder.calls).containsExactlyInAnyOrderElementsOf(JOBS.values());
                });
    }

    @Test
    void pendingDispatchAndUnavailableOwnerAreReportedAsFailures() {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=embedded",
                        "shop.job.enabled=true", "spring.profiles.active=api")
                .run(context -> {
                    Recorder recorder = context.getBean(Recorder.class);
                    recorder.pending = 2;
                    var pending = handlers(context).get("food-scheduled-dispatch").run(null);
                    assertThat(pending.status()).isEqualTo(JobStatus.FAILED);
                    assertThat(pending.detail()).contains("pending=2");
                    recorder.fail = true;
                    var down = handlers(context).get("food-queue-notice").run(null);
                    assertThat(down.status()).isEqualTo(JobStatus.FAILED);
                });
    }

    @Test
    void noFoodJobsWithoutPlatformJobRuntime() {
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=embedded",
                        "spring.profiles.active=api,ops")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(handlers(context)).doesNotContainKeys(JOBS.keySet().toArray(String[]::new));
                });
    }

    @Test
    void disabledFoodRegistersNoFoodJobs() {
        runner.withPropertyValues("shop.food.enabled=false", "shop.job.enabled=true",
                        "spring.profiles.active=api,ops")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(handlers(context)).doesNotContainKeys(JOBS.keySet().toArray(String[]::new));
                    assertThat(context.getBeansOfType(JobDeclaration.class).values())
                            .extracting(JobDeclaration::handlerName)
                            .doesNotContainAnyElementsOf(JOBS.keySet());
                });
    }

    private static Map<String, JobHandler> handlers(ApplicationContext context) {
        return context.getBeansOfType(JobHandler.class).values().stream()
                .collect(Collectors.toMap(JobHandler::name, Function.identity()));
    }

    static final class Recorder implements FoodMaintenanceService {
        final List<Task> calls = new ArrayList<>();
        int pending;
        boolean fail;

        @Override
        public Report run(Task task, int limit) {
            if (fail) throw new IllegalStateException("owner unavailable");
            assertThat(limit).isBetween(1, 200);
            calls.add(task);
            return new Report(1, task == Task.SCHEDULED_DISPATCH ? pending : 0);
        }
    }

    /** 嵌入宿主：food-core 的本地实现以 bean 形式存在（这里用记录器代替）。 */
    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean
        Recorder foodMaintenanceService() {
            return new Recorder();
        }

        @Bean
        DiningTransactionRecoveryService diningTransactionRecoveryService() {
            return limit -> new DiningTransactionRecoveryService.Report(0, 0, 0, 0);
        }
    }
}
