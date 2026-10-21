package ai.neargo.shop.beauty.server.config;

import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.job.JobTargetConfiguration;
import ai.neargo.shop.store.config.JobLockConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** A standalone API target shares registry and primary-database locks without starting a scheduler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Profile("api")
@Import({JobTargetConfiguration.class, JobLockConfiguration.class})
public class BeautyJobTargetConfiguration { }
