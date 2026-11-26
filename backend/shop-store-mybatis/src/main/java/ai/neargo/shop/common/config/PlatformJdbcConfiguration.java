package ai.neargo.shop.common.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 手写 SQL 用的 {@link JdbcTemplate}：主库的，和各模块句柄上的（平台、交易）。
 *
 * <p>平台基础库上的类点名 {@link #PLATFORM_JDBC_TEMPLATE}，门店交易库上的点名 {@link #TRADE_JDBC_TEMPLATE}；
 * 别的类照旧按类型注入，拿到的是主库那一个。
 * 主库那一个必须在这里显式声明：容器里一旦出现任何一个 {@code JdbcTemplate}，Spring Boot 自动配置的
 * 那一个就会退让，按类型注入的地方会悄悄拿到平台的 —— 没绑定时看不出区别，绑定那天才写错库。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformJdbcConfiguration {

    public static final String PLATFORM_JDBC_TEMPLATE = "platformJdbcTemplate";
    public static final String TRADE_JDBC_TEMPLATE = "tradeJdbcTemplate";

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(PLATFORM_JDBC_TEMPLATE)
    JdbcTemplate platformJdbcTemplate(@Qualifier(ModuleDataSources.PLATFORM_DATA_SOURCE) DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(TRADE_JDBC_TEMPLATE)
    JdbcTemplate tradeJdbcTemplate(@Qualifier(ModuleDataSources.TRADE_DATA_SOURCE) DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
