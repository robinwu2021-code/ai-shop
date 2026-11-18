package ai.neargo.shop.paybridge;

import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.paybridge.impl.PaymentCollectionServiceImpl;
import ai.neargo.shop.spi.pay.MarketPort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.payment-collections.enabled", havingValue = "true")
@EnableConfigurationProperties(PaymentCollectionProperties.class)
/** Sandbox collection delivery requires the platform's recoverable unified worker. */
public class PaymentCollectionConfiguration {
    public PaymentCollectionConfiguration(OutboxProperties outbox) {
        if (!outbox.unified("PLATFORM")) {
            throw new IllegalStateException(
                    "Payment collections require shop.outbox.sources.platform.mode=unified");
        }
    }

    @Bean
    PaymentCollectionSecurity paymentCollectionSecurity(PaymentCollectionProperties p) {
        return new PaymentCollectionSecurity(p, Clock.systemUTC());
    }

    @Bean
    PaymentCollectionService paymentCollectionService(
            DataSource ds, PlatformTransactionManager tx, OutboxEventBus events) {
        return new PaymentCollectionServiceImpl(ds, tx, events);
    }

    @Bean
    PaymentCollectionProjection paymentCollectionProjection(
            DataSource ds,
            PlatformTransactionManager tx,
            ObjectMapper json,
            MessageService messages,
            BizIdentityResolver identities,
            MarketPort markets) {
        return new PaymentCollectionProjection(
                ds, tx, json, messages, identities, markets, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    PaymentCollectionStream paymentCollectionStream(
            DataSource ds,
            PaymentCollectionProperties p,
            PaymentCollectionSecurity security,
            BizIdentityResolver identities,
            TokenStore tokens,
            PaymentCollectionProjection projection) {
        return new PaymentCollectionStream(
                ds, p, security, identities, tokens, projection, Clock.systemUTC());
    }
}
