package ai.neargo.shop.config;

import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台主库入口保持原类名；实际工厂及拦截器由共享持久层提供。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryPersistenceConfiguration.class)
public class PlatformDataSourceConfig {
}
