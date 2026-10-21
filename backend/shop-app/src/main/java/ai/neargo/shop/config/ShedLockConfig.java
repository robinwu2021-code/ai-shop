package ai.neargo.shop.config;

import ai.neargo.shop.store.config.JobLockConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Platform and standalone targets use the same primary-datasource lock implementation. */
@Configuration(proxyBeanMethods = false)
@Import(JobLockConfiguration.class)
public class ShedLockConfig { }
