package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.service.impl.ServiceDefinitionServiceImpl;
import ai.neargo.shop.beauty.service.impl.ServiceDefinitions;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Missing product authority rejects new publication; policy-only deployments remain bootable. */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(BeautyServiceDefinitionsConfiguration.class)
@ConditionalOnBeautyEnabled
public class BeautyDefinitionConfiguration {
    @Bean
    ServiceDefinitionService serviceDefinitionService(ServiceDefinitions definitions,
            BeautyMappers.ActionLogMapper actions, RequestIdempotencyPort idempotency,
            ObjectProvider<ServiceProductFactsPort> products,
            @Qualifier(ModuleDataSources.TRADE_TX_MANAGER) PlatformTransactionManager manager, ObjectMapper json) {
        return new ServiceDefinitionServiceImpl(definitions, actions, idempotency, products, manager, json);
    }
}
