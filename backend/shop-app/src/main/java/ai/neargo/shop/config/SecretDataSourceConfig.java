package ai.neargo.shop.config;

import ai.neargo.secret.core.DefaultKeyRegistry;
import ai.neargo.secret.core.DefaultSecretVault;
import ai.neargo.secret.core.InjectedMasterKeyProvider;
import ai.neargo.secret.core.MasterKeyBook;
import ai.neargo.secret.core.MasterKeyProvider;
import ai.neargo.secret.core.SecretLifecycleService;
import ai.neargo.secret.core.SecretSettings;
import ai.neargo.secret.core.SecretStoreMappers;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * 平台级密钥模块 {@code secret} 的装配（TDD-支付服务-W5密钥模块）：独立数据源与事务管理器、独立迁移、主密钥注入。
 *
 * <h2>照 {@link PayDataSourceConfig} 的形状，三处相同</h2>
 * <ol>
 *   <li><b>同一个库</b>，复用 {@code spring.datasource.*}，不是 {@code @Primary}；</li>
 *   <li><b>独立迁移目录</b> {@code db/secret}（在 secret-store 里），历史记在 {@code sec_flyway_history}，号从 V1 起；
 *       返回 {@link SecretMigrated} 而不是 {@code Flyway} —— 容器里出现 Flyway bean 平台迁移就整体不跑
 *       （{@code ArchitectureTest#noBeanMayExposeFlyway}）；</li>
 *   <li>与平台迁移<b>同开同关</b>：H2 测试关了平台 Flyway，这里也不跑，表来自生成的 {@code schema-test.sql}。</li>
 * </ol>
 *
 * <h2>一处不同：不装数据域拦截器以外的东西也照装</h2>
 * 工厂用平台<b>同一个</b> {@link MybatisPlusInterceptor}（乐观锁要跟过来）。{@code sec_*} 没有登记进数据域，拦截器对它们不起作用。
 *
 * <p>Mapper 归属：包 {@code ai.neargo.secret.store.mapper} 不在 {@code ai.neargo.shop} 之下，平台的全局扫描本来就够不着，
 * 只由这里的工厂扫描 —— 不需要再往 {@code MybatisPlusConfig} 的排除正则里加一项。
 */
@Configuration
@EnableConfigurationProperties(SecretProperties.class)
@MapperScan(basePackages = "ai.neargo.secret.store.mapper", sqlSessionFactoryRef = "secretSqlSessionFactory")
public class SecretDataSourceConfig {

    static final String SECRET_HISTORY_TABLE = "sec_flyway_history";

    /** 密钥迁移跑完的凭据。工厂依赖它，于是 Mapper 第一次查询前表一定已建好 */
    public record SecretMigrated(String historyTable, int applied) {
    }

    @Bean
    DataSource secretDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driver) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        // 密钥读写量很小（登记、启用、轮换、偶尔解一次材料），给 4 足够 —— 与平台池共享同一个库的连接上限
        ds.setMaximumPoolSize(4);
        ds.setPoolName("secret-pool");
        return ds;
    }

    @Bean
    PlatformTransactionManager secretTxManager(@Qualifier("secretDataSource") DataSource ds) {
        var manager = new DataSourceTransactionManager(ds);
        manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
        return manager;
    }

    @Bean
    SecretMigrated secretFlyway(@Qualifier("secretDataSource") DataSource ds,
                                ObjectProvider<FlywayMigrationInitializer> platformFlyway,
                                @Value("${shop.secret.flyway.enabled:true}") boolean enabled,
                                @Value("${shop.secret.flyway.locations:classpath:db/secret}") String locations) {
        if (platformFlyway.getIfAvailable() == null) {
            return new SecretMigrated(SECRET_HISTORY_TABLE, 0);
        }
        return migrateSecret(ds, enabled, locations);
    }

    static SecretMigrated migrateSecret(DataSource ds, boolean enabled, String locations) {
        if (!enabled) {
            return new SecretMigrated(SECRET_HISTORY_TABLE, 0);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations(locations.split(","))
                .table(SECRET_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        return new SecretMigrated(SECRET_HISTORY_TABLE, flyway.migrate().migrationsExecuted);
    }

    /** 与 {@code paySqlSessionFactory} 同一套理由：填充器必须装、GlobalConfig 必须新建（见那边的注释） */
    @Bean
    SqlSessionFactory secretSqlSessionFactory(@Qualifier("secretDataSource") DataSource ds,
                                              MybatisPlusInterceptor interceptor,
                                              MetaObjectHandler metaObjectHandler,
                                              SecretMigrated secretMigrated) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setPlugins(interceptor);
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(metaObjectHandler);
        bean.setGlobalConfig(global);
        return bean.getObject();
    }

    @Bean
    SecretSettings secretSettings(SecretProperties props) {
        return new SecretSettings(props.env(), Clock.systemUTC());
    }

    @Bean
    MasterKeyProvider secretMasterKeyProvider(SecretProperties props) {
        return new InjectedMasterKeyProvider(props.masterKeys().stream()
                .map(k -> new InjectedMasterKeyProvider.Entry(k.alias(), k.namespace(), k.material(), k.active()))
                .toList());
    }

    @Bean
    SecretStoreMappers secretStoreMappers(SecretMappers.MasterKeyMapper masterKeys,
                                          SecretMappers.SecretBlobMapper blobs,
                                          SecretMappers.KeyPolicyMapper policies,
                                          SecretMappers.KeyMapper keys,
                                          SecretMappers.KeyVersionMapper versions,
                                          SecretMappers.KeyEventMapper events,
                                          SecretMappers.AccessLogMapper accessLogs) {
        return new SecretStoreMappers(masterKeys, blobs, policies, keys, versions, events, accessLogs);
    }

    @Bean
    MasterKeyBook secretMasterKeyBook(SecretStoreMappers db, MasterKeyProvider provider, SecretSettings settings) {
        return new MasterKeyBook(db.masterKeys(), provider, settings);
    }

    @Bean
    DefaultSecretVault secretVault(SecretStoreMappers db, MasterKeyBook book, MasterKeyProvider provider,
                                   SecretSettings settings,
                                   @Qualifier("secretTxManager") PlatformTransactionManager tx) {
        return new DefaultSecretVault(db, book, provider, settings, tx);
    }

    @Bean
    DefaultKeyRegistry secretKeyRegistry(SecretStoreMappers db, DefaultSecretVault vault, SecretSettings settings,
                                         @Qualifier("secretTxManager") PlatformTransactionManager tx) {
        return new DefaultKeyRegistry(db, vault, settings, tx);
    }

    @Bean
    SecretLifecycleService secretLifecycleService(SecretStoreMappers db, MasterKeyBook book,
                                                  MasterKeyProvider provider, DefaultSecretVault vault,
                                                  SecretSettings settings,
                                                  @Qualifier("secretTxManager") PlatformTransactionManager tx) {
        return new SecretLifecycleService(db, book, provider, vault, settings, tx);
    }

    /**
     * 启动时把注入的主密钥对进 {@code sec_master_key}（新名字登记、active 那把升为 ACTIVE、原 ACTIVE 降为只解密）。
     * 依赖迁移：表建好之后、全部单例就绪时做一次。没有注入主密钥的进程什么都不做。
     */
    @Bean
    SmartInitializingSingleton secretMasterKeySync(MasterKeyBook book, SecretMigrated secretMigrated) {
        return book::sync;
    }
}
