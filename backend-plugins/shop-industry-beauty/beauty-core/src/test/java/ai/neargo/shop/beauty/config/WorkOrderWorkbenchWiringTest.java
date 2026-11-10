package ai.neargo.shop.beauty.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.beauty.mapper.WorkOrderWorkbenchMapper;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkOrderWorkbenchWiringTest {
    @Test
    void disabledIsIndependentAndEnabledUsesItsRealReadDependencies() {
        var base =
                new ApplicationContextRunner()
                        .withUserConfiguration(BeautyWorkbenchConfiguration.class);
        base.withPropertyValues("shop.beauty.enabled=false")
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .hasNotFailed()
                                        .doesNotHaveBean(WorkOrderWorkbenchService.class));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            base.withPropertyValues("shop.beauty.enabled=true")
                    .withBean(AppointmentAccess.class, () -> mock(AppointmentAccess.class))
                    .withBean(
                            AppointmentPersistence.class, () -> mock(AppointmentPersistence.class))
                    .withBean(
                            WorkOrderWorkbenchMapper.class,
                            () -> mock(WorkOrderWorkbenchMapper.class))
                    .run(
                            ctx ->
                                    assertThat(ctx)
                                            .hasNotFailed()
                                            .hasSingleBean(WorkOrderWorkbenchService.class));
        }
    }
}
