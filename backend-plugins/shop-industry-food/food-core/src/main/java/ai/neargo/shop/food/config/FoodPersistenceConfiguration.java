package ai.neargo.shop.food.config;

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
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@MapperScan(basePackages = "ai.neargo.shop.food.mapper", markerInterface = BaseMapper.class,
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
            @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
        var factory = new MapperFactoryBean<>(ConfigRevisionMapper.class);
        factory.setSqlSessionTemplate(template);
        return factory;
    }

    @Bean
    @DependsOnDatabaseInitialization
    FoodSchemaReady foodSchemaReady(@Qualifier("dataSource") DataSource dataSource, FoodProperties properties) {
        FoodSchemaReady schema = new FoodSchemaValidator().initialize(dataSource, properties.migration().mode());
        if (!schema.ready()) throw new IllegalStateException("Food primary prerequisites missing: " + schema.missingCapabilities());
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

    public static final class PersistenceEnabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"off".equalsIgnoreCase(context.getEnvironment().getProperty("shop.food.migration.mode", "validate"));
        }
    }
}
