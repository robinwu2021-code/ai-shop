package ai.neargo.shop.config;

import ai.neargo.shop.job.JobTargetConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Retains the platform configuration entry while sharing target registration with standalone services. */
@Configuration(proxyBeanMethods = false)
@Import(JobTargetConfiguration.class)
public class JobTargetConfig { }
