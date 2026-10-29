package ai.neargo.shop.beauty.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/** Enables private schema validation after the host has established its primary persistence owner. */
@AutoConfiguration(afterName = {"com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ConditionalOnBeautyEnabled
@Import(BeautyPersistenceConfiguration.class)
public class BeautyPersistenceAutoConfiguration {
}
