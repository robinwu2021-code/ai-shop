package ai.neargo.shop.food.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
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

    static final class PersistenceEnabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"off".equalsIgnoreCase(context.getEnvironment().getProperty("shop.food.migration.mode", "validate"));
        }
    }
}
