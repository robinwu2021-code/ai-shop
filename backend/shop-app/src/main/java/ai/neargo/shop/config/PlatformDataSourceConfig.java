package ai.neargo.shop.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 平台自己的数据源、SqlSessionFactory 与事务管理器 —— <b>显式声明，不再靠自动配置</b>。
 *
 * <h2>为什么必须有这个类</h2>
 * 进销存是**独立库**（{@code shop-inventory}），它带进来第二个 {@code DataSource}、
 * 第二个 {@code SqlSessionFactory}、第二个 {@code PlatformTransactionManager}。
 * 而 Spring Boot 这三处的自动配置都是 {@code @ConditionalOnMissingBean}：
 * {@code DataSourceAutoConfiguration}、{@code MybatisPlusAutoConfiguration}、
 * {@code DataSourceTransactionManagerAutoConfiguration}。
 * <b>只要出现第二个，它们就整体退让</b> —— 平台这一侧连自己的那一套都没有了。
 *
 * <h2>不写这个类会怎样（按发现顺序，三条都验过）</h2>
 * <ol>
 *   <li>平台实体 {@code updateById} 报
 *       「Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found」——
 *       因为全部 Mapper 绑到了进销存那个<b>刻意不装拦截器</b>的工厂上；</li>
 *   <li>顺着往下才发现<b>连 DataScope 拦截器也一起丢了</b>，而那是行级越权防线：
 *       商家能查到别家的数据，且不报错；</li>
 *   <li>最隐蔽的是数据源本身：{@code spring.sql.init} 只作用于主数据源，
 *       主数据源没了之后，平台的建表脚本会灌进进销存那个库 ——
 *       <b>两套表挤在同一个库里，而测试照样全绿</b>，「两个库分开了」这件事根本没发生。</li>
 * </ol>
 *
 * <h2>三条实现约束</h2>
 * <ul>
 *   <li><b>复用 Spring Boot 与 MyBatis-Plus 自己的 Properties 对象</b>，不手抄配置：
 *       {@code spring.datasource.*}、{@code mybatis-plus.configuration.*}、
 *       {@code global-config.*}（含逻辑删除那三项）一个不漏，且 profile 覆盖照常生效
 *       （{@code opsdb} 换 url 这类）。</li>
 *   <li>{@code MybatisPlusProperties.getConfiguration()} 返回的是 {@code CoreConfiguration}
 *       —— 它是属性载体不是 Configuration 本体，要照 MyBatis-Plus 自动配置那样
 *       {@code applyTo} 一份 {@link MybatisConfiguration}，否则
 *       {@code map-underscore-to-camel-case} 静默失效。</li>
 *   <li><b>三个 Bean 都要 {@code @Primary}</b>。少标一个，那一处就会在两个候选之间
 *       按类型乱挑 —— 而挑错的表现不是启动失败，是运行到某一行才炸。</li>
 * </ul>
 */
@Configuration
public class PlatformDataSourceConfig {

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
    SqlSessionFactory sqlSessionFactory(DataSource dataSource, MybatisPlusProperties properties,
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
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig = properties.getGlobalConfig();
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
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
