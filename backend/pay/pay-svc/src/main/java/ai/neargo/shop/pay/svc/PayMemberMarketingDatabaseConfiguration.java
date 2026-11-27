package ai.neargo.shop.pay.svc;

import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/** Standalone Pay keeps its original database as primary when the rights ledger is split out. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(ModuleDataSources.MEMBER_MARKETING_BINDING)
@Import({PrimaryPersistenceConfiguration.class, ModuleDataSourceRegistrar.class})
@MapperScan(basePackages = "ai.neargo.shop.pay.mapper", sqlSessionFactoryRef = "sqlSessionFactory",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = ModuleDataSources.MEMBER_MARKETING_MAPPER_PATTERN))
@MapperScan(basePackages = {"ai.neargo.shop.pay.mapper.entitlement", "ai.neargo.shop.pay.mapper.storedvalue"},
        sqlSessionFactoryRef = ModuleDataSources.MEMBER_MARKETING_SQL_SESSION_FACTORY)
public class PayMemberMarketingDatabaseConfiguration {
    @Bean
    static BeanFactoryPostProcessor standalonePayAliases() {
        return factory -> {
            factory.registerAlias("dataSource", "payDataSource");
            factory.registerAlias("sqlSessionFactory", "paySqlSessionFactory");
            factory.registerAlias("transactionManager", "payTxManager");
        };
    }
}
