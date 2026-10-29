package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.beauty.service.impl.StorePolicyServiceImpl;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.idem.IdempotencyService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

/** Wires the first local-atomic business slice without scanning unrelated domains. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
@EnableTransactionManagement
public class BeautyPolicyConfiguration {
    @Bean
    StorePolicyService storePolicyService(BeautyMappers.StorePolicyMapper policies,
            BeautyMappers.StorePolicyVersionMapper versions, BeautyMappers.ActionLogMapper actions,
            IdempotencyService idempotency, @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("dataSource") DataSource source, ObjectMapper json) {
        return new StorePolicyServiceImpl(policies, versions, actions, idempotency, publisher, source, json);
    }
}
