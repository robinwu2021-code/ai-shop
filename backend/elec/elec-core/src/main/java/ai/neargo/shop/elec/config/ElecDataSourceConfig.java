package ai.neargo.shop.elec.config;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import java.time.LocalDateTime;

/**
 * 元器件的<b>独立数据源</b>（{@code ai_shop_elec}）。照 {@code InventoryDataSourceConfig} 的形状写，
 * 它踩过的三个坑这里一个都不能再踩：
 * <ol>
 *   <li><b>三处注入都 {@code @Qualifier}</b>：平台数据源挂着 {@code @Primary}，按类型注入永远拿到它，
 *       参数名叫 elecDataSource 没有用。拿错的症状是「一切正常」—— 表建进 ai_shop，零报错。
 *       {@link #mustBeOwnDataSource} 是它的保险</li>
 *   <li><b>迁移的凭证不是 {@code Flyway} 类型</b>：容器里出现任何一个 Flyway bean，
 *       平台自己的迁移就整体退让（ArchitectureTest#noBeanMayExposeFlyway 盯着）</li>
 *   <li><b>不暴露 {@code TransactionTemplate} bean</b>：Spring Boot 的那个是
 *       {@code @ConditionalOnMissingBean}，这里一声明，平台那个就退让了，按类型注入的人拿到的是本库的事务。
 *       需要编程式事务的 Service 自己拿 {@code elecTransactionManager} 包一个</li>
 *   <li><b>新建 GlobalConfig</b>，不用共享的 defaults()：在共享实例上装填充器会污染平台那套工厂</li>
 * </ol>
 *
 * <p>Mapper 两头夹：这里按 {@code elec.mapper} 包绑到本工厂，平台 {@code MybatisPlusConfig}
 * 的全局扫描把 {@code ai.neargo.shop.elec} 排除掉。
 *
 * <p>不装平台的 DataScope / 分页 / 乐观锁拦截器：本域只认 supplier_no / buyer_ref，
 * 独立出去时平台的线程上下文不存在。
 */
@Configuration
@EnableConfigurationProperties(ElecProperties.class)
@ConditionalOnProperty(prefix = "shop.elec", name = "enabled", havingValue = "true")
@MapperScan(basePackages = "ai.neargo.shop.elec.mapper", sqlSessionFactoryRef = "elecSqlSessionFactory")
public class ElecDataSourceConfig {

    static final String POOL = "elec-pool";

    /** 本域自己的 Flyway 历史表，不与平台的 flyway_schema_history 混用 */
    static final String HISTORY_TABLE = "elc_flyway_history";

    @Bean
    DataSource elecDataSource(ElecProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getDatasource().getUrl());
        ds.setUsername(props.getDatasource().getUsername());
        ds.setPassword(props.getDatasource().getPassword());
        ds.setMaximumPoolSize(props.getDatasource().getMaxPoolSize());
        ds.setPoolName(POOL);
        return ds;
    }

    /** 迁移跑完的凭证。<b>刻意不是 {@code Flyway} 类型</b>，见类注释第 2 条。 */
    public record ElecMigrated(String historyTable) {
    }

    @Bean
    ElecMigrated elecFlyway(@Qualifier("elecDataSource") DataSource elecDataSource, ElecProperties props) {
        mustBeOwnDataSource(elecDataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(elecDataSource)
                // 逗号分隔的多个位置要拆开传：整串当一个位置时 Flyway 找不到任何脚本，
                // 只打一行 WARN「No migrations found」然后照常启动 —— 库是空的，第一次查询才炸
                .locations(java.util.Arrays.stream(props.getFlywayLocations().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new))
                // 位置不存在就启动失败，不要带着一个空库起来
                .failOnMissingLocations(true)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        if (props.isFlywayEnabled()) {
            flyway.migrate();
        }
        return new ElecMigrated(HISTORY_TABLE);
    }

    @Bean
    SqlSessionFactory elecSqlSessionFactory(@Qualifier("elecDataSource") DataSource elecDataSource,
                                            ElecMigrated elecFlyway) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(elecDataSource);
        bean.setTypeAliasesPackage("ai.neargo.shop.elec.entity");
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(timestamps());
        bean.setGlobalConfig(global);
        return bean.getObject();
    }

    @Bean
    PlatformTransactionManager elecTransactionManager(@Qualifier("elecDataSource") DataSource elecDataSource) {
        return new DataSourceTransactionManager(elecDataSource);
    }

    /** 只补时间戳；「谁改的」由 Service 显式写 */
    private static MetaObjectHandler timestamps() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }

    /** 拿到的必须是本域自己的连接池 —— 拿错时零报错地写进平台库，只能在这里拦 */
    private static void mustBeOwnDataSource(DataSource ds) {
        String pool = ds instanceof HikariDataSource h ? h.getPoolName() : ds.getClass().getSimpleName();
        if (!POOL.equals(pool)) {
            throw new IllegalStateException("元器件注入到的不是自己的数据源（pool=" + pool + "）—— "
                    + "迁移与全部 elc_* 读写会打到平台库上，且不会有任何报错。拒绝启动。");
        }
    }
}
