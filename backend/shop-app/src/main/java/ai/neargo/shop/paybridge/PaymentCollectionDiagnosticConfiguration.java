package ai.neargo.shop.paybridge;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("api")
@ConditionalOnProperty(name = "shop.payment-collections.diagnostic.enabled", havingValue = "true")
@EnableConfigurationProperties(PaymentCollectionDiagnosticProperties.class)
public class PaymentCollectionDiagnosticConfiguration {
    @Bean
    PaymentCollectionDiagnosticJob paymentCollectionDiagnosticJob(PaymentCollectionStream stream,
            PaymentCollectionProperties collections, PaymentCollectionDiagnosticProperties diagnostics) {
        if (!collections.isEnabled() || !"SANDBOX".equals(collections.getEnvironment()))
            throw new IllegalStateException("SSE diagnostics require enabled SANDBOX collections");
        return new PaymentCollectionDiagnosticJob(stream, diagnostics, Clock.systemUTC());
    }
}
