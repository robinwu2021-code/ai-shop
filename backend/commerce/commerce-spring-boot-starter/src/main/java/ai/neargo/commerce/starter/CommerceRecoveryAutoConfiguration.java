package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.RefundFacade;
import ai.neargo.commerce.core.fulfillment.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.persistence.JdbcRecoveryRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration(
        after = {CommerceFulfillmentAutoConfiguration.class, CommerceRefundAutoConfiguration.class})
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.enabled", "mall.payment.enabled"},
        havingValue = "true")
public class CommerceRecoveryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MallRecoveryFacade.class)
    MallRecoveryFacade commerceMallRecovery(
            DataSource ds,
            CommerceUnitOfWork uow,
            PaymentFacade payments,
            ObjectProvider<RefundFacade> refunds) {
        return new DefaultMallRecoveryFacade(
                new JdbcRecoveryRepository(ds), uow, payments, refunds.getIfAvailable());
    }
}
