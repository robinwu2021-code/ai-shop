package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.mall.RefundFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.fulfillment.CommerceRefundService;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.persistence.JdbcRefundRepository;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

import java.time.Clock;

import javax.sql.DataSource;

/** Refund execution requires explicit opt-in and the trusted payment/authorization ports. */
@AutoConfiguration(after = CommerceFulfillmentAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.enabled", "mall.payment.enabled", "mall.refund.enabled"},
        havingValue = "true")
public class CommerceRefundAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RefundRepository.class)
    @DependsOn("commerceMigrationInitializer")
    RefundRepository commerceRefundRepository(DataSource ds, CommerceIdGenerator ids) {
        return new JdbcRefundRepository(ds, ids);
    }

    @Bean
    @ConditionalOnMissingBean(RefundFacade.class)
    RefundFacade commerceRefundFacade(
            MallRepository mall,
            RefundRepository refunds,
            FulfillmentRepository fulfillment,
            SubscriptionRepository subscriptions,
            SubscriptionAccountRepository accounts,
            EndScheduleService schedule,
            CommerceUnitOfWork uow,
            ActorAuthorizationPort authorization,
            PlatformPaymentPort payment) {
        return new CommerceRefundService(
                mall,
                refunds,
                fulfillment,
                subscriptions,
                accounts,
                schedule,
                uow,
                authorization,
                payment,
                Clock.systemUTC());
    }
}
