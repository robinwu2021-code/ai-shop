package ai.neargo.shop.beauty.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Enables beauty business components without taking over the platform's authentication or datasource. */
@AutoConfiguration
@ConditionalOnBeautyEnabled
@EnableConfigurationProperties(BeautyProperties.class)
public class BeautyAutoConfiguration {
}
