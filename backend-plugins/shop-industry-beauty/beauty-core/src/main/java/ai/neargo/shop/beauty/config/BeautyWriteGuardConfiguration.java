package ai.neargo.shop.beauty.config;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.PlatformPublicationPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import java.util.Set;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Reuses platform write infrastructure without scanning other business domains or creating an outbox runtime. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyWriteGuardConfiguration {
    /** Standalone fallback stays local; the host retains sole ownership of its Spring Mapper bean. */
    @Bean
    @ConditionalOnMissingBean(IdempotencyService.class)
    IdempotencyService beautyIdempotencyService(
            @Qualifier("beautySqlSessionTemplate") SqlSessionTemplate template, ObjectMapper json) {
        var configuration = template.getSqlSessionFactory().getConfiguration();
        if (!configuration.hasMapper(SysIdempotentMapper.class)) {
            configuration.addMapper(SysIdempotentMapper.class);
        }
        return new IdempotencyService(template.getMapper(SysIdempotentMapper.class), json);
    }

    /** Delivery needs the owner route; immutable publication evidence can opt into a narrow archive contract. */
    @Bean
    @ConditionalOnMissingBean(name = "beautyOutboxPublisher")
    OutboxPublisher beautyOutboxPublisher(ObjectProvider<UnifiedOutboxFacade> facades,
            @Qualifier("transactionManager") PlatformTransactionManager manager, BeautySchemaReady schema,
            @Qualifier("dataSource") ObjectProvider<DataSource> sources, ObjectMapper json,
            ObjectProvider<OutboxConsumer> consumers,
            @Value("${shop.beauty.publication.archive-only:false}") boolean archiveOnly,
            @Value("${shop.outbox.maintenance:false}") boolean maintenance) {
        if (!"5".equals(schema.version())) {
            throw new IllegalStateException("Beauty write guards require validated primary schema");
        }
        return PlatformPublicationPublisher.create(facades::getIfAvailable, manager, sources::getObject, json,
                consumers::orderedStream, Set.of("BEAUTY_STORE_POLICY_VERSION_PUBLISHED",
                        "BEAUTY_SERVICE_DEFINITION_VERSION_PUBLISHED",
                        "BEAUTY_APPOINTMENT_OPERATION_COMPLETED", "BEAUTY_RECEPTION_STATE_CHANGED"),
                archiveOnly, maintenance);
    }
}
