package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.config.BeautySourceConfiguration;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Disabled installation must not demand a database or fabricate a shared checkout provider. */
class BeautySourceWiringTest {
    @Test
    void disabledDoesNotRegisterSourceServicesOrRequireOwners() {
        new ApplicationContextRunner()
                .withUserConfiguration(BeautySourceConfiguration.class)
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(ServiceSubmissionService.class)
                                        .doesNotHaveBean(WorkOrderBillService.class));
    }
}
