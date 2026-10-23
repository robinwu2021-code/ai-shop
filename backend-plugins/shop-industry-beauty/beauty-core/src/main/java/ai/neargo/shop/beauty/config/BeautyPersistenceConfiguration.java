package ai.neargo.shop.beauty.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Beauty owns mapper activation, while the platform retains the primary factory and transaction manager. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
@MapperScan(basePackages = "ai.neargo.shop.beauty.mapper", markerInterface = BaseMapper.class,
        sqlSessionTemplateRef = "beautySqlSessionTemplate")
public class BeautyPersistenceConfiguration {
    @Bean
    BeautyDataScopeRegistration beautyDataScopeRegistration() {
        return new BeautyDataScopeRegistration();
    }

    @Bean
    @ConditionalOnMissingBean(name = "beautySchemaReady")
    BeautySchemaReady beautySchemaReady(@Qualifier("dataSource") DataSource source,
            @Value("${shop.beauty.migration.mode:validate}") String mode) {
        new BeautySchemaValidator().initialize(source, mode);
        return new BeautySchemaReady("7");
    }

    @Bean
    SqlSessionTemplate beautySqlSessionTemplate(@Qualifier("sqlSessionFactory") SqlSessionFactory factory,
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager, BeautySchemaReady schema) {
        if (!"7".equals(schema.version()) || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager primary) || primary.getDataSource() != source) {
            throw new IllegalStateException("Beauty requires the platform primary factory and transaction manager");
        }
        return new SqlSessionTemplate(factory);
    }
}
