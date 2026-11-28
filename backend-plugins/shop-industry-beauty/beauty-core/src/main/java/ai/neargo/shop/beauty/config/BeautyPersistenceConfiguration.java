package ai.neargo.shop.beauty.config;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.IndustryTableMapper;
import ai.neargo.shop.common.config.TradeTableMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Beauty owns mapper activation, while the platform retains the primary factory and transaction manager. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
// 美业的预约、服务定义在门店交易库（ADR-068）：按标记收到交易句柄；门店策略与服务执行（工单、任务、步骤、
// 绑定、提交、参与）在行业库：按标记收到行业句柄；设计里还没去处的表留在原模板
@MapperScan(basePackages = "ai.neargo.shop.beauty.mapper", markerInterface = TradeTableMapper.class,
        sqlSessionTemplateRef = "beautyTradeSqlSessionTemplate")
@MapperScan(basePackages = "ai.neargo.shop.beauty.mapper", markerInterface = IndustryTableMapper.class,
        sqlSessionTemplateRef = "beautyIndustrySqlSessionTemplate")
@MapperScan(basePackages = "ai.neargo.shop.beauty.mapper", markerInterface = BaseMapper.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {TradeTableMapper.class, IndustryTableMapper.class}),
        sqlSessionTemplateRef = "beautySqlSessionTemplate")
public class BeautyPersistenceConfiguration {
    @Bean
    BeautyDataScopeRegistration beautyDataScopeRegistration() {
        return new BeautyDataScopeRegistration();
    }

    @Bean
    @ConditionalOnMissingBean(name = "beautySchemaReady")
    BeautySchemaReady beautySchemaReady(@Qualifier("dataSource") DataSource source,
            @Qualifier(ModuleDataSources.INDUSTRY_DATA_SOURCE) DataSource industry,
            @Value("${shop.beauty.migration.mode:validate}") String mode) {
        new BeautySchemaValidator().initialize(source, mode);
        // 行业表在行业句柄上核对（行业句柄没绑定时就是主库）
        BeautyIndustrySchemaCheck.verify(industry);
        // Highest installed migration may be10; this token proves only the unchanged V1-V9 base.
        return new BeautySchemaReady("9");
    }

    @Bean
    SqlSessionTemplate beautySqlSessionTemplate(
            @Qualifier("sqlSessionFactory") SqlSessionFactory factory,
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            BeautySchemaReady schema) {
        // Execution capability is rechecked lazily by its local verifier; primary identities remain mandatory.
        if (!"9".equals(schema.version())
                || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager primary)
                || primary.getDataSource() != source) {
            throw new IllegalStateException(
                    "Beauty requires the platform primary factory and transaction manager");
        }
        return new SqlSessionTemplate(factory);
    }

    @Bean
    SqlSessionTemplate beautyTradeSqlSessionTemplate(
            @Qualifier(ModuleDataSources.TRADE_SQL_SESSION_FACTORY) SqlSessionFactory factory,
            @Qualifier(ModuleDataSources.TRADE_DATA_SOURCE) DataSource source,
            @Qualifier(ModuleDataSources.TRADE_TX_MANAGER) PlatformTransactionManager manager,
            BeautySchemaReady schema) {
        // 与主库模板同一条要求：工厂、数据源、事务管理器必须是同一个库（交易句柄未绑定时就是主库）
        if (!"9".equals(schema.version())
                || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager trade)
                || trade.getDataSource() != source) {
            throw new IllegalStateException(
                    "Beauty trade mappers require one trade factory, datasource and transaction manager");
        }
        return new SqlSessionTemplate(factory);
    }

    @Bean
    SqlSessionTemplate beautyIndustrySqlSessionTemplate(
            @Qualifier(ModuleDataSources.INDUSTRY_SQL_SESSION_FACTORY) SqlSessionFactory factory,
            @Qualifier(ModuleDataSources.INDUSTRY_DATA_SOURCE) DataSource source,
            @Qualifier(ModuleDataSources.INDUSTRY_TX_MANAGER) PlatformTransactionManager manager,
            BeautySchemaReady schema) {
        // 同一条要求：工厂、数据源、事务管理器必须是同一个库（行业句柄未绑定时就是主库）
        if (!"9".equals(schema.version())
                || factory.getConfiguration().getEnvironment().getDataSource() != source
                || !(manager instanceof DataSourceTransactionManager industry)
                || industry.getDataSource() != source) {
            throw new IllegalStateException(
                    "Beauty industry mappers require one industry factory, datasource and transaction manager");
        }
        return new SqlSessionTemplate(factory);
    }
}
