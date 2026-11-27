package ai.neargo.shop.beauty.config;

import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.idem.port.RequestIdempotencyPortImpl;
import ai.neargo.shop.spi.idempotency.RequestIdempotencyPort;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/** Reuses platform write infrastructure without scanning other business domains or creating an outbox runtime. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyWriteGuardConfiguration {
    /** Standalone fallback stays local; the host retains sole ownership of its Spring Mapper bean. */
    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyPort.class)
    RequestIdempotencyPort beautyRequestIdempotency(
            @Qualifier("beautySqlSessionTemplate") SqlSessionTemplate template, ObjectMapper json) {
        var configuration = template.getSqlSessionFactory().getConfiguration();
        if (!configuration.hasMapper(SysIdempotentMapper.class)) {
            configuration.addMapper(SysIdempotentMapper.class);
        }
        return new RequestIdempotencyPortImpl(template.getMapper(SysIdempotentMapper.class), json);
    }
}
