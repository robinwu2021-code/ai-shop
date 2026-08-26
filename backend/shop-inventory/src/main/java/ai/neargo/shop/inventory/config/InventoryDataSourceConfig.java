package ai.neargo.shop.inventory.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import java.time.LocalDateTime;

/**
 * 进销存的**独立数据源**装配。
 *
 * <p><b>默认关闭</b>（{@code shop.inventory.enabled=false}）。这一条是 S0「零行为变化」的全部理由：
 * 加一个硬性的第二数据源，等于所有存量部署当天都要多准备一个库才起得来。
 * 打开它是一次配置改动，不是一次发版。
 *
 * <p><b>三件与平台数据源刻意不同的事：</b>
 * <ol>
 *   <li><b>不是 {@code @Primary}</b>：平台那套仍然是默认数据源，注入点一个都不受影响。</li>
 *   <li><b>不装 DataScope 拦截器</b>：本领域只认 {@code ownerId}，不认平台的行级数据域。
 *       装上之后 shop-inventory 就依赖了 {@code BizContext} 的线程上下文，
 *       而**独立交付时那个上下文不存在** —— 这是「可独立交付」这条约束在装配层的样子。</li>
 *   <li><b>自己的 Flyway 与自己的历史表</b>：迁移号从 V1 重新开始，与平台那边互不知情。</li>
 * </ol>
 *
 * <p>Mapper 的归属靠两头夹：这里按 {@code inventory.mapper} 包显式绑到本工厂，
 * 平台的 {@code MybatisPlusConfig} 那个全局扫描把 {@code ai.neargo.shop.inventory} 排除掉。
 * 少任何一头，{@code inv_*} 的查询都会打到平台库上 —— 而它只会在跑到那一行时才炸。
 */
@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
@MapperScan(basePackages = "ai.neargo.shop.inventory.mapper",
        sqlSessionFactoryRef = "invSqlSessionFactory")
public class InventoryDataSourceConfig {

    /** 迁移脚本位置。与平台的 {@code classpath:db/migration} **必须不同目录**。 */
    static final String MIGRATION_LOCATION = "classpath:db/inventory";

    /** 本领域自己的 Flyway 历史表，不与平台的 {@code flyway_schema_history} 混用。 */
    static final String HISTORY_TABLE = "inv_flyway_history";

    @Bean
    DataSource invDataSource(InventoryProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getDatasource().getUrl());
        ds.setUsername(props.getDatasource().getUsername());
        ds.setPassword(props.getDatasource().getPassword());
        ds.setMaximumPoolSize(props.getDatasource().getMaxPoolSize());
        ds.setPoolName("inv-pool");
        return ds;
    }

    /**
     * 迁移在数据源之后、SqlSessionFactory 之前跑 —— 靠参数依赖表达顺序，不靠 {@code @DependsOn} 的字符串。
     */
    @Bean
    Flyway invFlyway(DataSource invDataSource, InventoryProperties props) {
        Flyway flyway = Flyway.configure()
                .dataSource(invDataSource)
                .locations(MIGRATION_LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        if (props.isFlywayEnabled()) {
            flyway.migrate();
        }
        return flyway;
    }

    @Bean
    SqlSessionFactory invSqlSessionFactory(DataSource invDataSource, Flyway invFlyway) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(invDataSource);
        bean.setTypeAliasesPackage("ai.neargo.shop.inventory.entity");
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/inventory/*.xml"));
        // 刻意不 setPlugins(...)：平台的 DataScope / 分页 / 乐观锁**拦截器**都不装到这里。
        // 但**填充器要装** —— 它不改 SQL 语义，只补两个时间戳；不装的话 MyBatis-Plus
        // 会把 null 显式写进 INSERT，顶掉 DDL 上的 DEFAULT CURRENT_TIMESTAMP
        GlobalConfig global = GlobalConfigUtils.defaults();
        global.setMetaObjectHandler(invMetaObjectHandler());
        bean.setGlobalConfig(global);
        bean.setConfiguration(new MybatisConfiguration());
        return bean.getObject();
    }

    /** 只补时间戳。**不猜 createdBy** —— 「谁改的」由 Service 显式写。 */
    private MetaObjectHandler invMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(org.apache.ibatis.reflection.MetaObject metaObject) {
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(org.apache.ibatis.reflection.MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }

    @Bean
    PlatformTransactionManager invTransactionManager(DataSource invDataSource) {
        return new DataSourceTransactionManager(invDataSource);
    }
}
