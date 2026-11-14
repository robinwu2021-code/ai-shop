package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.service.impl.ServiceDefinitionServiceImpl;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Missing product authority rejects new publication; policy-only deployments remain bootable. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyDefinitionConfiguration {
    @Bean
    ServiceDefinitionService serviceDefinitionService(BeautyMappers.ServiceDefinitionMapper heads,
            BeautyMappers.ServiceDefinitionVersionMapper versions, BeautyMappers.ServiceRequirementMapper requirements,
            BeautyMappers.ActionLogMapper actions, IdempotencyService idempotency,
            @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher,
            ObjectProvider<ServiceProductFactsPort> products, PlatformTransactionManager manager,
            @Qualifier("dataSource") DataSource source, ObjectMapper json) {
        return new ServiceDefinitionServiceImpl(heads, versions, requirements, actions, idempotency, publisher,
                products, manager, source, json);
    }
}
