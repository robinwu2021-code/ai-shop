package ai.neargo.shop.food.config;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.IndustryTableMapper;
import ai.neargo.shop.common.config.TradeTableMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodProductionEvidenceMapper;
import org.mybatis.spring.mapper.MapperFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
// 餐饮的菜品、菜单、购物车、批次、账单、退菜、订位等交易表在门店交易库（ADR-068）：按标记收到交易句柄；
// 桌台、工位、出品单、上菜道次、厨显事件、门店策略、排队、寄存在行业库：按标记收到行业句柄；
// 库存同步、结账表头、收款意图等还没去处的表留在原模板
@MapperScan(basePackages = "ai.neargo.shop.food.mapper", markerInterface = TradeTableMapper.class,
        sqlSessionTemplateRef = "foodTradeSqlSessionTemplate")
@MapperScan(basePackages = "ai.neargo.shop.food.mapper", markerInterface = IndustryTableMapper.class,
        sqlSessionTemplateRef = "foodIndustrySqlSessionTemplate")
@MapperScan(basePackages = "ai.neargo.shop.food.mapper", markerInterface = BaseMapper.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {TradeTableMapper.class, IndustryTableMapper.class}),
        sqlSessionTemplateRef = "foodSqlSessionTemplate")
public class FoodPersistenceConfiguration {
    @Bean
    MapperFactoryBean<FoodProductionEvidenceMapper> foodProductionEvidenceMapper(
            @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
        var factory = new MapperFactoryBean<>(FoodProductionEvidenceMapper.class);
        factory.setSqlSessionTemplate(template);
        return factory;
    }

    /** Explicit registration keeps immutable publications outside generic BaseMapper update/delete APIs. */
    @Bean
    MapperFactoryBean<ConfigRevisionMapper> foodConfigRevisionMapper(
            @Qualifier("foodIndustrySqlSessionTemplate") SqlSessionTemplate template) {
        var factory = new MapperFactoryBean<>(ConfigRevisionMapper.class);
        factory.setSqlSessionTemplate(template);
        return factory;
    }

    @Bean
    @DependsOnDatabaseInitialization
    FoodSchemaReady foodSchemaReady(@Qualifier("dataSource") DataSource dataSource,
            @Qualifier(ModuleDataSources.TRADE_DATA_SOURCE) DataSource trade, FoodProperties properties) {
        FoodSchemaReady schema = new FoodSchemaValidator().initialize(dataSource, trade, properties.migration().mode());
        if (!schema.ready()) throw new IllegalStateException("Food prerequisites missing: " + schema.missingCapabilities());
        return schema;
    }

    @Bean
    FoodDataScopeRegistration foodDataScopeRegistration() { return new FoodDataScopeRegistration(); }

    /** Gate mapper access on schema validation while retaining the platform factory and transaction manager. */
    @Bean
    SqlSessionTemplate foodSqlSessionTemplate(@Qualifier("sqlSessionFactory") SqlSessionFactory factory,
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager, FoodSchemaReady schema) {
        if (!schema.ready() || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager primary) || primary.getDataSource() != source)
            throw new IllegalStateException("Food requires the platform primary factory and transaction manager");
        return new SqlSessionTemplate(factory);
    }

    /** 交易句柄上的模板：与主库模板同一条要求，工厂、数据源、事务管理器必须是同一个库（未绑定时就是主库）。 */
    @Bean
    SqlSessionTemplate foodTradeSqlSessionTemplate(
            @Qualifier(ModuleDataSources.TRADE_SQL_SESSION_FACTORY) SqlSessionFactory factory,
            @Qualifier(ModuleDataSources.TRADE_DATA_SOURCE) DataSource source,
            @Qualifier(ModuleDataSources.TRADE_TX_MANAGER) PlatformTransactionManager manager, FoodSchemaReady schema) {
        if (!schema.ready() || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager trade) || trade.getDataSource() != source)
            throw new IllegalStateException("Food trade mappers require one trade factory, datasource and transaction manager");
        return new SqlSessionTemplate(factory);
    }

    /** 行业句柄上的模板：同一条要求，工厂、数据源、事务管理器必须是同一个库（未绑定时就是主库）。 */
    @Bean
    SqlSessionTemplate foodIndustrySqlSessionTemplate(
            @Qualifier(ModuleDataSources.INDUSTRY_SQL_SESSION_FACTORY) SqlSessionFactory factory,
            @Qualifier(ModuleDataSources.INDUSTRY_DATA_SOURCE) DataSource source,
            @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager manager, FoodSchemaReady schema) {
        if (!schema.ready() || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager industry) || industry.getDataSource() != source)
            throw new IllegalStateException("Food industry mappers require one industry factory, datasource and transaction manager");
        return new SqlSessionTemplate(factory);
    }

    public static final class PersistenceEnabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"off".equalsIgnoreCase(context.getEnvironment().getProperty("shop.food.migration.mode", "validate"));
        }
    }
}
