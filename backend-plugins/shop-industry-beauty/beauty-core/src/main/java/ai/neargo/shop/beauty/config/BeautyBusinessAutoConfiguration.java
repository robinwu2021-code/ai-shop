package ai.neargo.shop.beauty.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

/** Business wiring follows primary schema and the platform JSON codec; private-schema probes remain independent. */
@AutoConfiguration(after = BeautyPersistenceAutoConfiguration.class,
        afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnBeautyEnabled
@ConditionalOnBean(ObjectMapper.class)
@Import({BeautyWriteGuardConfiguration.class, BeautyPolicyConfiguration.class,
        BeautyDefinitionConfiguration.class, BeautyCatalogConfiguration.class, BeautyAppointmentConfiguration.class})
public class BeautyBusinessAutoConfiguration {
}
