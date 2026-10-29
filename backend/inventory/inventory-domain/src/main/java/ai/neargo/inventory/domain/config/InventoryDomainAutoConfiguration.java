package ai.neargo.inventory.domain.config;

import ai.neargo.inventory.domain.spi.InventoryJobRunner;
import ai.neargo.inventory.domain.spi.LoggingJobRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 进销存领域的装配入口。**内嵌（ai-shop 过渡期）与独立部署走同一个入口**。
 *
 * <p><b>为什么是自动装配而不是让宿主去扫包</b>：包名是 {@code ai.neargo.inventory}，
 * 宿主的组件扫描（ai-shop 扫 {@code ai.neargo.shop}）够不着它。让每个宿主各自记得
 * 「再加一个扫描路径」，漏掉的那个宿主会在第一次调用时报 NoSuchBean，而不是启动时。
 *
 * <p>每个领域 Bean 自己挂 {@link ConditionalOnInventory}：{@code inventory.enabled=false} 时一个都不装。
 */
@AutoConfiguration
// 排除自身：本类也在被扫的包里，不排除会被当成普通配置类再注册一遍
@ComponentScan(basePackages = "ai.neargo.inventory.domain", excludeFilters = @ComponentScan.Filter(
        type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class))
public class InventoryDomainAutoConfiguration {

    /** 宿主没给运行记录时的兜底；ai-shop 内嵌时由它自己的任务记录接管。 */
    @Bean
    @ConditionalOnMissingBean(InventoryJobRunner.class)
    InventoryJobRunner inventoryJobRunner() {
        return new LoggingJobRunner();
    }
}
