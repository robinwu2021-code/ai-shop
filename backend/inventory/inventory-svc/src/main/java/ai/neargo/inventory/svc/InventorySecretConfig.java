package ai.neargo.inventory.svc;

import ai.neargo.secret.core.DigestCredentialVerifier;
import ai.neargo.secret.core.SecretSettings;
import ai.neargo.secret.core.SecretStoreMappers;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 独立进程自己连密钥库（TDD-基础能力库对齐「1h 开放应用」Q18）：开放凭证存在密钥库（只存摘要），
 * 密钥模块是库不是服务，验证在本进程里做 —— 装一套指向同一个密钥库（ai-shop 主库的 {@code sec_*}）的数据源，
 * 只给 {@link DigestCredentialVerifier} 用。内嵌在 ai-shop 时不走这里，用 shop-app 的 {@code SecretDataSourceConfig}。
 *
 * <p><b>不跑密钥库迁移</b>：{@code sec_*} 的迁移归 shop-app；本进程的账号只该有那几张表的读写。
 * {@code inventory.secret.flyway-enabled} 只给测试在空库上建表用。
 *
 * <p>数据源、事务管理器、工厂都标 {@code defaultCandidate = false}：本进程的「默认」数据源仍是进销存自己的那套，
 * 按类型注入的地方（MyBatis 自动装配的 {@code SqlSessionTemplate}、未写名字的事务）不会撞上第二个候选。
 */
@Configuration
@MapperScan(basePackages = "ai.neargo.secret.store.mapper", sqlSessionFactoryRef = "invSecretSqlSessionFactory")
public class InventorySecretConfig {

    static final String HISTORY_TABLE = "sec_flyway_history";

    /** 密钥库迁移跑完（或不跑）的凭据：工厂依赖它，测试里表一定先于第一次查询建好 */
    public record SecretSchemaReady(int applied) {
    }

    @Bean(defaultCandidate = false)
    DataSource invSecretDataSource(@Value("${inventory.secret.datasource.url}") String url,
                                   @Value("${inventory.secret.datasource.username}") String username,
                                   @Value("${inventory.secret.datasource.password}") String password,
                                   @Value("${inventory.secret.datasource.max-pool-size:2}") int maxPoolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        // 只有签发、验证、列出、吊销，量很小；与 ai-shop 主库共用连接上限
        ds.setMaximumPoolSize(maxPoolSize);
        ds.setPoolName("inv-secret-pool");
        return ds;
    }

    @Bean(defaultCandidate = false)
    PlatformTransactionManager invSecretTxManager(@Qualifier("invSecretDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    SecretSchemaReady invSecretSchema(@Qualifier("invSecretDataSource") DataSource ds,
                                      @Value("${inventory.secret.flyway-enabled:false}") boolean enabled) {
        if (!enabled) {
            return new SecretSchemaReady(0);
        }
        return new SecretSchemaReady(Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/secret")
                .table(HISTORY_TABLE)
                .load()
                .migrate().migrationsExecuted);
    }

    /** 与 shop-app 的密钥工厂同形：乐观锁拦截器、审计列填充器（时间列一律 UTC，见 DDL 注释） */
    @Bean(defaultCandidate = false)
    SqlSessionFactory invSecretSqlSessionFactory(@Qualifier("invSecretDataSource") DataSource ds,
                                                 SecretSchemaReady schema) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        bean.setPlugins(interceptor);
        // 必须新建 GlobalConfig：共享的默认实例上挂填充器会串到进销存那套工厂（见 InventoryDataSourceConfig）
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new SecretAuditFill());
        bean.setGlobalConfig(global);
        return bean.getObject();
    }

    @Bean
    SecretSettings invSecretSettings(@Value("${inventory.secret.env:}") String env) {
        return new SecretSettings(env, Clock.systemUTC());
    }

    @Bean
    SecretStoreMappers invSecretStoreMappers(SecretMappers.MasterKeyMapper masterKeys,
                                             SecretMappers.SecretBlobMapper blobs,
                                             SecretMappers.KeyPolicyMapper policies,
                                             SecretMappers.KeyMapper keys,
                                             SecretMappers.KeyVersionMapper versions,
                                             SecretMappers.KeyEventMapper events,
                                             SecretMappers.AccessLogMapper accessLogs) {
        return new SecretStoreMappers(masterKeys, blobs, policies, keys, versions, events, accessLogs);
    }

    /** 开放凭证只用 DIGEST 档：不需要主密钥，本进程也不注入任何主密钥 */
    @Bean
    DigestCredentialVerifier invCredentialVerifier(SecretStoreMappers db, SecretSettings settings,
                                                   @Qualifier("invSecretTxManager") PlatformTransactionManager tx) {
        return new DigestCredentialVerifier(db, settings, tx);
    }

    /** 本进程没有登录用户：审计人记 SYSTEM，谁签发的另记在密钥事件与访问日志里 */
    static final class SecretAuditFill implements MetaObjectHandler {

        private static final String OPERATOR = "SYSTEM";

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            strictInsertFill(metaObject, "createdBy", String.class, OPERATOR);
            strictInsertFill(metaObject, "updatedBy", String.class, OPERATOR);
            strictInsertFill(metaObject, "deleted", Integer.class, 0);
            strictInsertFill(metaObject, "version", Long.class, 0L);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now(ZoneOffset.UTC));
            strictUpdateFill(metaObject, "updatedBy", String.class, OPERATOR);
        }
    }
}
