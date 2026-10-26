package ai.neargo.shop.commerce.server;

import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 独立进程仍使用原平台主库装配，未来拆库只调整部署配置。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryPersistenceConfiguration.class)
public class CommercePersistenceConfiguration {}
