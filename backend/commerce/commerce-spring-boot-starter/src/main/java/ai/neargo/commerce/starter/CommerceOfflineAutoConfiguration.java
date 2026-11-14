package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.fulfillment.PaymentFacade;
import ai.neargo.commerce.api.mall.MallFacade;
import ai.neargo.commerce.api.mall.RefundFacade;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.DefaultMallFacade;
import ai.neargo.commerce.core.mall.MallRepository;
import ai.neargo.commerce.core.offline.DefaultOfflinePurchaseFacade;
import ai.neargo.commerce.core.offline.OfflineRepository;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.persistence.JdbcOfflineRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

import java.time.Clock;

import javax.sql.DataSource;

/**
 * Explicit opt-in for the offline (bank transfer) purchase workflow. It needs the platform payment
 * coordination already enabled, a trusted host {@link OfflineCollectionPort} and {@link
 * PrivateEvidencePort}, and an explicit collection profile number. Any of them missing fails
 * startup instead of silently installing a facade that cannot verify money or evidence.
 */
@AutoConfiguration(after = {CommerceFulfillmentAutoConfiguration.class, CommerceRefundAutoConfiguration.class})
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.enabled", "mall.payment.enabled", "mall.offline.enabled"},
        havingValue = "true")
public class CommerceOfflineAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ai.neargo.commerce.api.port.OfflineCashRefundPort.class)
    @DependsOn("commerceMigrationInitializer")
    ai.neargo.commerce.api.port.OfflineCashRefundPort commerceOfflineCashRefunds(DataSource ds,CommerceUnitOfWork uow,
            OfflineCollectionPort collection,ai.neargo.commerce.api.port.PlatformPaymentPort payments) {
        return new ai.neargo.commerce.core.offline.DefaultOfflineCashRefundPort(new ai.neargo.commerce.persistence.JdbcCashRefundRepository(ds),uow,collection,payments,Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(OfflineRepository.class)
    @DependsOn("commerceMigrationInitializer")
    OfflineRepository commerceOfflineRepository(DataSource ds) {
        return new JdbcOfflineRepository(ds);
    }

    @Bean
    @ConditionalOnMissingBean(OfflinePurchaseFacade.class)
    OfflinePurchaseFacade commerceOfflinePurchaseFacade(
            MallRepository orders,
            FulfillmentRepository operations,
            OfflineRepository offline,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort authorization,
            MallFacade mall,
            PaymentFacade payments,
            ObjectProvider<RefundFacade> refunds,
            ObjectProvider<ai.neargo.commerce.core.mall.RefundRepository> normalRefunds,
            OfflineCollectionPort collection,
            ai.neargo.commerce.api.port.OfflineCashRefundPort cashRefunds,
            PrivateEvidencePort evidence,
            CommerceIdGenerator ids,
            org.springframework.core.env.Environment env) {
        String profileNo = env.getRequiredProperty("commerce.mall.offline.profile-no");
        if (!(mall instanceof DefaultMallFacade core))
            throw new IllegalStateException(
                    "commerce.mall.offline.enabled requires the commerce core MallFacade");
        return new DefaultOfflinePurchaseFacade(
                orders,
                operations,
                offline,
                accounts,
                uow,
                stores,
                authorization,
                core,
                payments,
                refunds.getIfAvailable(),
                collection,
                evidence,
                Clock.systemUTC(),
                ids,
                profileNo.trim()).cashRefunds(cashRefunds).normalRefunds(normalRefunds.getIfAvailable()).testStores(java.util.Arrays.stream(env.getProperty("commerce.mall.offline.test-stores", "").split(",")).map(String::trim).filter(s->!s.isBlank()).collect(java.util.stream.Collectors.toSet()));
    }
}
