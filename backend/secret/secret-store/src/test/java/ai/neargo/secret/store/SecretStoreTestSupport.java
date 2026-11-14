package ai.neargo.secret.store;

import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 起一个 H2 内存库，<b>跑真迁移 {@code db/secret}</b>（历史表 {@code sec_flyway_history}），再装一个与宿主同形的 MyBatis-Plus 工厂。
 *
 * <p>与 job-store 读生成的 H2 脚本不同，这里直接跑生产迁移：{@code sec_*} 不 ALTER 任何平台表，H2 MySQL 模式能原样执行 ——
 * 于是「迁移在 H2 上跑得通」本身就被每个用例验一遍，不需要第二份 schema。
 */
public final class SecretStoreTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private SecretStoreTestSupport() {
    }

    /** 一个用例一套库与工厂 */
    public record Store(DataSource dataSource, SqlSessionTemplate session, PlatformTransactionManager txManager,
                        int migrationsApplied) {

        public <T> T mapper(Class<T> type) {
            return session.getMapper(type);
        }
    }

    public static Store freshStore() {
        String mysqlUrl = System.getProperty("secret.mysql.url");
        DataSource ds = mysqlUrl == null || mysqlUrl.isBlank() ? h2() : mysql();
        int applied = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/secret")
                .table("sec_flyway_history")
                .load()
                .migrate().migrationsExecuted;
        try {
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(ds);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            bean.setConfiguration(configuration);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            bean.setPlugins(interceptor);
            GlobalConfig global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig());
            global.setMetaObjectHandler(new AuditFill());
            bean.setGlobalConfig(global);
            SqlSessionFactory factory = bean.getObject();
            // Mapper 在工厂建好、GlobalConfig 绑定之后再登记 —— 先登记的话填充器找不到，INSERT 里审计列全是 null
            for (Class<?> mapper : SecretMappers.class.getDeclaredClasses()) {
                factory.getConfiguration().addMapper(mapper);
            }
            return new Store(ds, new SqlSessionTemplate(factory), new DataSourceTransactionManager(ds), applied);
        } catch (Exception e) {
            throw new IllegalStateException("secret store fixture failed", e);
        }
    }

    private static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:secretstore" + SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }

    /**
     * {@code -Dsecret.mysql.url=jdbc:mysql://127.0.0.1:3306/}（库名留空）时：每个用例建一个新库 {@code sec_t<pid>_<n>}。
     * 只给本机临时实例用 —— 它会 CREATE DATABASE。
     */
    private static DataSource mysql() {
        String base = System.getProperty("secret.mysql.url");
        String user = System.getProperty("secret.mysql.user", "root");
        String password = System.getProperty("secret.mysql.password", "");
        String db = "sec_t" + ProcessHandle.current().pid() + "_" + SEQ.incrementAndGet();
        try (Connection c = DriverManager.getConnection(base, user, password);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create scratch database on " + base, e);
        }
        return new DriverManagerDataSource(base + db, user, password);
    }

    /** 与平台 AuditMetaObjectHandler 同口径（测试里没有登录用户，一律 SYSTEM） */
    static final class AuditFill implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            strictInsertFill(metaObject, "createdBy", String.class, "SYSTEM");
            strictInsertFill(metaObject, "updatedBy", String.class, "SYSTEM");
            strictInsertFill(metaObject, "deleted", Integer.class, 0);
            strictInsertFill(metaObject, "version", Long.class, 0L);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        }
    }
}
