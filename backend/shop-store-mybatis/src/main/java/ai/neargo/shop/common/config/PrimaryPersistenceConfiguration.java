package ai.neargo.shop.common.config;

import ai.neargo.common.data.scope.DataScopeHandler;
import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * 平台与独立业务进程复用的主库装配。
 * 原 bean 名、主库标识、属性、填充器及数据域→分页→乐观锁次序保持一致；不扫描业务 Mapper。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MybatisPlusProperties.class)
public class PrimaryPersistenceConfiguration {


    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource, MybatisPlusProperties properties,
                                        MybatisPlusInterceptor interceptor,
                                        ObjectProvider<MetaObjectHandler> metaObjectHandler,
                                        ObjectProvider<ISqlInjector> sqlInjector,
                                        ObjectProvider<IdentifierGenerator> identifierGenerator)
            throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPlugins(interceptor);

        MybatisConfiguration configuration = new MybatisConfiguration();
        if (properties.getConfiguration() != null) {
            properties.getConfiguration().applyTo(configuration);
        }
        bean.setConfiguration(configuration);
        /*
         * ⚠️ **自动配置会往 GlobalConfig 里塞三个可选 Bean，手写工厂必须一并塞**。
         *
         * 最要命的是 MetaObjectHandler（AuditMetaObjectHandler）：它负责填
         * createdAt / createdBy / updatedAt。漏了它，MyBatis-Plus 会把 null 显式写进
         * INSERT，顶掉 DDL 上的 DEFAULT CURRENT_TIMESTAMP —— 报的是
         * 「created_at 不能为空」，而排查方向会指向建表脚本。
         *
         * 另两个（SQL 注入器、主键生成器）当前没有自定义实现，
         * 但仍然按 ObjectProvider 取：将来谁加了一个，不必回头改这里。
         */
        GlobalConfig globalConfig = properties.getGlobalConfig();
        metaObjectHandler.ifAvailable(globalConfig::setMetaObjectHandler);
        sqlInjector.ifAvailable(globalConfig::setSqlInjector);
        identifierGenerator.ifAvailable(globalConfig::setIdentifierGenerator);
        bean.setGlobalConfig(globalConfig);
        if (properties.resolveMapperLocations().length > 0) {
            bean.setMapperLocations(properties.resolveMapperLocations());
        }
        return bean.getObject();
    }

    @Bean
    @Primary
    SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 平台的事务管理器。
     *
     * <p>这一个最容易漏：进销存的 {@code invTransactionManager} 也是
     * {@code PlatformTransactionManager}，于是
     * {@code DataSourceTransactionManagerAutoConfiguration} 同样退让 ——
     * 而 {@code @Transactional} 不写 {@code transactionManager} 时按类型找，
     * 两个候选没有 {@code @Primary} 就直接启动失败或选错库。
     */
    @Bean
    @Primary
    PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        var manager = new DataSourceTransactionManager(dataSource);
        manager.setTransactionSynchronization(
                AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
        return manager;
    }


    @Bean
    DataScopeTableRegistry dataScopeTableRegistry(List<DataScopeRegistrar> registrars) {
        DataScopeTableRegistry registry = new DataScopeTableRegistry();
        registrars.forEach(r -> r.register(registry));
        return registry;
    }

    @Bean
    DataScopeHandler dataScopeHandler(DataScopeTableRegistry registry) {
        return new DataScopeHandler(registry);
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(DataScopeHandler dataScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopeHandler));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
