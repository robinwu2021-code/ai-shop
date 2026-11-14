package ai.neargo.shop.merchant.paysync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StoreSyncProperties.class)
public class StoreSyncConfiguration {
}
