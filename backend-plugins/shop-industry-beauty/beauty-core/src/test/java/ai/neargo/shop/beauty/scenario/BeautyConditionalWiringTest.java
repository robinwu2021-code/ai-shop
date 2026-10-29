package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Guards activation, including the explicit deployment decision required by the beauty spec. */
class BeautyConditionalWiringTest {
    private Class<?> configuration() {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.config.BeautyAutoConfiguration"),
                "beauty must provide its own conditional business configuration");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(configuration()));
    }

    @Test
    void workOrderWiringHasNoCycle() throws Exception {
        // Direct import must honor the disabled switch without resolving any local business dependency.
        new ApplicationContextRunner().withUserConfiguration(Class.forName(
                "ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration"))
                .withPropertyValues("shop.beauty.enabled=false").run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean("workOrderCoordinator").doesNotHaveBean("workOrderServiceImpl")
                        .doesNotHaveBean("receptionRecovery").doesNotHaveBean("receptionRecoveryJob"));
        // Native WorkOrderScopeIT starts these actual strong constructor beans and exercises both services.
    }

    @Test
    void discoversBeautyConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains("ai.neargo.shop.beauty.config.BeautyAutoConfiguration");
    }

    @Test
    void defaultDisabledDoesNotBindMissingDeployment() {
        runner().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(configuration()));
    }

    @Test
    void explicitFalseDoesNotLoadBusinessConfiguration() {
        runner().withPropertyValues("shop.beauty.enabled=false", "shop.beauty.deployment=invalid")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(configuration()));
    }

    @Test
    void enabledWithoutDeploymentFailsClosed() {
        runner().withPropertyValues("shop.beauty.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "standalone"})
    void explicitlySelectedDeploymentLoadsBusiness(String deployment) {
        runner().withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=" + deployment)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(configuration()));
    }

    @Test
    void invalidDeploymentFailsClosed() {
        runner().withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=invalid")
                .run(context -> assertThat(context).hasFailed());
    }
}
