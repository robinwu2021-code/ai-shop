package ai.neargo.shop.commerce.channel.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CommerceMockAutoConfigurationTest {
    @TempDir Path directory;
    private final String token = "local-config-test-token-32-characters";

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(CommerceMockAutoConfiguration.class)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().findAndAddModules().build())
                .withPropertyValues("spring.profiles.active=commerce-mock",
                        "shop.commerce.mock.enabled=true", "shop.commerce.mock.token=" + token);
    }

    @Test void defaultModeStartsEmbeddedPeer() {
        runner().withPropertyValues("shop.commerce.mock.directory=" + directory,
                "shop.commerce.mock.port=0").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MockCommercePlatform.class)
                    .hasSingleBean(MockCommerceGateway.class);
            assertThat(context.getBean(MockCommercePlatform.class).port()).isPositive();
        });
    }

    @Test void externalModeNeedsNoEmbeddedDirectoryAndCallsExistingHttpContract() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        try (var peer = new MockCommercePlatform(json, directory, token, 0)) {
            peer.start();
            runner().withPropertyValues("shop.commerce.mock.base-url=http://127.0.0.1:" + peer.port())
                    .run(context -> {
                        assertThat(context).hasNotFailed().doesNotHaveBean(MockCommercePlatform.class);
                        var gateway = context.getBean(MockCommerceGateway.class);
                        var scope = new ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway.AuthorizationContext(
                                "AUTH", "E", "A", "MOCK_PROFILE", "EA", "ACTOR", "STATE", "MOCK_MERCHANT");
                        assertThat(gateway.begin(scope)).isNotNull();
                    });
        }
    }

    @Test void externalModeStillRejectsProduction() {
        runner().withPropertyValues("spring.profiles.active=commerce-mock,prod",
                "shop.commerce.mock.base-url=http://127.0.0.1:9410")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void ordinaryDeploymentHasNoMockBeans() {
        runner().withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(MockCommercePlatform.class).doesNotHaveBean(MockCommerceGateway.class));
    }
}
