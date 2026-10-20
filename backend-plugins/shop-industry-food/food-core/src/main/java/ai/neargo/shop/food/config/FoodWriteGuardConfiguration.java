package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.PlatformOutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Reuses platform write infrastructure without scanning other business domains or creating an outbox runtime. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodWriteGuardConfiguration {
    @Bean
    @ConditionalOnMissingBean(SysIdempotentMapper.class)
    MapperFactoryBean<SysIdempotentMapper> sysIdempotentMapper(
            @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
        var mapper = new MapperFactoryBean<>(SysIdempotentMapper.class);
        mapper.setSqlSessionTemplate(template);
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyService.class)
    IdempotencyService idempotencyService(SysIdempotentMapper mapper, ObjectMapper json) {
        return new IdempotencyService(mapper, json);
    }

    /** No legacy fallback: the existing unified PLATFORM source must be registered by its owner. */
    @Bean
    @ConditionalOnMissingBean(name = "foodOutboxPublisher")
    OutboxPublisher foodOutboxPublisher(ObjectProvider<UnifiedOutboxFacade> facades,
            @Qualifier("transactionManager") PlatformTransactionManager manager, FoodSchemaReady schema) {
        if (!schema.ready()) throw new IllegalStateException("Food write guards require validated primary schema");
        return new PlatformOutboxPublisher(facades::getIfAvailable, manager);
    }
}
