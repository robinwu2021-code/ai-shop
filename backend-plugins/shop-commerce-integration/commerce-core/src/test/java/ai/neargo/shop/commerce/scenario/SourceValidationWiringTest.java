package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.commerce.config.CommerceSourceValidationAutoConfiguration;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SourceValidationWiringTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommerceSourceValidationAutoConfiguration.class));
    ChannelSourceValidationProvider provider(Owner owner) {
        return ChannelSourceValidationRegistryTest.provider(owner, ChannelSourceValidationRegistryTest.valid(),
                new AtomicInteger());
    }

    @Test void absentProvidersDoNotFabricatePortAndLegacyPortAloneSurvives() {
        runner.run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(ChannelSourceValidationPort.class));
        ChannelSourceValidationPort legacy = source -> ChannelSourceValidationRegistryTest.valid();
        runner.withBean("legacy", ChannelSourceValidationPort.class, () -> legacy)
                .run(c -> assertThat(c.getBean(ChannelSourceValidationPort.class)).isSameAs(legacy));
    }

    @Test void twoIndustriesCreateOnePortWithoutPersistenceOrCommerceEnablement() {
        runner.withBean("food", ChannelSourceValidationProvider.class, () -> provider(Owner.FOOD))
                .withBean("retail", ChannelSourceValidationProvider.class, () -> provider(Owner.RETAIL))
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(ChannelSourceValidationPort.class));
    }

    @Test void enablingBeanOverrideCannotSilentlyReplaceANamedLegacyPort() {
        runner.withAllowBeanDefinitionOverriding(true)
                .withBean("food", ChannelSourceValidationProvider.class, () -> provider(Owner.FOOD))
                .withBean("channelSourceValidationPort", ChannelSourceValidationPort.class,
                        () -> source -> ChannelSourceValidationRegistryTest.valid())
                .run(c -> assertThat(c).hasFailed());
    }

    @Test void duplicateOwnerAndLegacyProviderMixturesFailStartup() {
        runner.withBean("food", ChannelSourceValidationProvider.class, () -> provider(Owner.FOOD))
                .withBean("another", ChannelSourceValidationProvider.class, () -> provider(Owner.FOOD))
                .run(c -> assertThat(c).hasFailed());
        runner.withBean("food", ChannelSourceValidationProvider.class, () -> provider(Owner.FOOD))
                .withBean("legacy", ChannelSourceValidationPort.class,
                        () -> source -> ChannelSourceValidationRegistryTest.valid())
                .run(c -> assertThat(c).hasFailed());
    }
}
