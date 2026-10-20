package ai.neargo.shop.beauty.server.config;

import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Standalone uses the same primary factory and audit filler as retail, with no secondary industry database. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
@Import({PrimaryPersistenceConfiguration.class, AuditMetaObjectHandler.class})
public class BeautyPrimaryPersistenceConfiguration {
}
