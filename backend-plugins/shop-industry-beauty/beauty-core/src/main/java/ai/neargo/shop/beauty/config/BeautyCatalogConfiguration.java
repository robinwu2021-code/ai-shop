package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.impl.ServiceCatalogQueryServiceImpl;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** No fallback catalog facts: absent product/directory authority rejects catalog reads. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyCatalogConfiguration {
    @Bean
    ServiceCatalogQueryService serviceCatalogQueryService(BeautyMappers.StorePolicyMapper policies,
            BeautyMappers.StorePolicyVersionMapper versions, BeautyMappers.ServiceDefinitionMapper definitions,
            BeautyMappers.ServiceDefinitionVersionMapper definitionVersions,
            ObjectProvider<ServiceCatalogFactsPort> facts, ObjectMapper json, PlatformTransactionManager manager) {
        return new ServiceCatalogQueryServiceImpl(policies, versions, definitions, definitionVersions, facts,
                json, manager);
    }
}
