package ai.neargo.shop.commerce.channel.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * No mock bean exists in ordinary deployments; simultaneous production profiles are rejected
 * explicitly.
 */
@AutoConfiguration(
        beforeName = {
            "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceChannelAutoConfiguration"
        })
@Profile("commerce-mock")
@ConditionalOnProperty(name = "shop.commerce.mock.enabled", havingValue = "true")
public class CommerceMockAutoConfiguration {
    @Bean(destroyMethod = "close")
    MockCommercePlatform mockCommercePlatform(
            ObjectMapper json,
            Environment env,
            @Value("${shop.commerce.mock.directory}") String directory,
            @Value("${shop.commerce.mock.token}") String token,
            @Value("${shop.commerce.mock.port:9410}") int port)
            throws IOException {
        rejectProduction(env);
        var platform = new MockCommercePlatform(json, Path.of(directory), token, port);
        platform.start();
        return platform;
    }

    @Bean
    MockCommerceGateway mockCommerceGateway(
            MockCommercePlatform platform,
            ObjectMapper json,
            Environment env,
            @Value("${shop.commerce.mock.profile-no:MOCK_PROFILE}") String profile,
            @Value("${shop.commerce.mock.token}") String token,
            @Value("${shop.commerce.mock.market:TW}") String market,
            @Value("${shop.commerce.mock.identity-namespace:MOCK:TW}") String namespace,
            @Value("${shop.commerce.mock.product-code:DELIVERY}") String product,
            @Value("${shop.commerce.mock.protocol-code:MOCK_V1}") String protocol,
            @Value("${shop.commerce.mock.account-scoped:false}") boolean accountScoped) {
        rejectProduction(env);
        return new MockCommerceGateway(
                profile,
                URI.create("http://localhost:" + platform.port()),
                token,
                json,
                new MockCommerceGateway.Metadata(
                        market, namespace, product, protocol, accountScoped));
    }

    private static void rejectProduction(Environment env) {
        for (String profile : env.getActiveProfiles())
            if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"))
                throw new IllegalStateException("Mock forbidden in production");
    }
}
