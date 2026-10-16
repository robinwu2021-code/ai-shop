package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import ai.neargo.shop.pay.points.job.PointsJobServices;
import ai.neargo.shop.payclient.PointsJobHandlers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDate;
import java.util.Map;

class PointsJobWiringTest {
    @Test
    void jobs_are_registered_disabled_by_default_and_invalid_batches_do_not_run_business_work() {
        var jobs = mock(PointsJobServices.class);
        new ApplicationContextRunner()
                .withPropertyValues("shop.job.enabled=true")
                .withBean(PointsJobServices.class, () -> jobs)
                .withUserConfiguration(PointsJobHandlers.class)
                .run(
                        context -> {
                            var handlers = context.getBeansOfType(JobHandler.class).values();
                            var declarations =
                                    context.getBeansOfType(JobDeclaration.class).values();
                            assertThat(handlers).hasSize(8);
                            assertThat(declarations).hasSize(8).allMatch(d -> !d.enabled());
                            assertThat(handlers)
                                    .extracting(JobHandler::name)
                                    .doesNotContain(
                                            "points-activate", "points-expire", "points-identity");
                            for (var handler : handlers) {
                                var invocation =
                                        new JobInvocation(
                                                "TEST",
                                                TriggerType.MANUAL,
                                                LocalDate.of(2026, 10, 16),
                                                Map.of("typeCode", "TEST", "limit", "201"));
                                assertThat(handler.run(invocation).status())
                                        .isEqualTo(JobStatus.FAILED);
                            }
                            verifyNoInteractions(jobs);
                        });
    }
}
