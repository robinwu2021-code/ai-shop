package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.auth.store.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.DbTokenStore;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.community.mapper.CommunityMappers.*;
import ai.neargo.shop.config.SessionProfiles;
import ai.neargo.shop.merchant.auth.MerchantIdentityLoader;
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.user.auth.ConsumerIdentityLoader;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

final class AccessMysqlFixture implements AutoCloseable {
    final String schema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "");
    final javax.sql.DataSource ds;
    final JdbcTemplate admin, jdbc;
    final DataSourceTransactionManager manager;
    final SessionDao dao;
    final LoginLogWriter audit;
    final CompositeIdentityLoader<LoginUser> loader;
    final SqlSessionTemplate mybatis;
    final AuthCache<String, DbTokenStore.CachedSession> sc;
    final AuthCache<String, LoginUser> ic;
    final Clock clock;
    final DbTokenStore store;

    AccessMysqlFixture() throws Exception {
        this(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    AccessMysqlFixture(Clock clock) throws Exception { this(clock, java.util.function.UnaryOperator.identity()); }

    AccessMysqlFixture(Clock clock, java.util.function.UnaryOperator<javax.sql.DataSource> wrapper) throws Exception {
        this.clock = clock;
        String url = System.getenv("COMMERCE_MYSQL_URL"),
                user = System.getenv("COMMERCE_MYSQL_USER"),
                password = System.getenv("COMMERCE_MYSQL_PASSWORD");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"));
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE DATABASE " + schema);
        ds = wrapper.apply(new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), user, password));
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        var ddl =
                new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String table :
                List.of(
                        "cmt_community",
                        "cmt_pickup_point",
                        "mch_session",
                        "mch_login_log",
                        "mch_account",
                        "usr_account",
                        "mch_entity",
                        "mch_qualification",
                        "mch_payment_merchant",
                        "sys_audit_log",
                        "mch_entity_apply",
                        "mch_entity_plan",
                        "sys_merchant_plan_def",
                        "mch_store",
                        "mch_store_role",
                        "mch_role", "ops_session", "sys_ops_staff", "sys_role", "sys_role_point", "sys_function_point")) {
            var m =
                    Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?\n\\);")
                            .matcher(ddl);
            assertTrue(m.find(), table);
            jdbc.execute(m.group());
        }
        jdbc.update(
                "INSERT INTO usr_account(user_no,created_at,updated_at) VALUES ('U',NOW(),NOW())");
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(mch_account_no,user_no,entity_no,is_owner,created_at,updated_at)"
                    + " VALUES ('MA','U','M',1,NOW(),NOW())");
        jdbc.update(
                "INSERT INTO mch_entity(entity_no,name,created_at,updated_at) VALUES"
                        + " ('M','merchant',NOW(),NOW())");
        jdbc.update(
                "INSERT INTO"
                    + " mch_store(entity_no,store_no,name,commerce_model,business_type_code,created_at,updated_at)"
                    + " VALUES ('M','S','store','STORE_V1','RETAIL',NOW(),NOW())");
        var cfg = new MybatisConfiguration();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(cfg);
        var plugin = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        plugin.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner
                        .OptimisticLockerInnerInterceptor());
        factory.setPlugins(plugin);
        factory.setGlobalConfig(
                new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
        factory.setDataSource(ds);
        mybatis = new SqlSessionTemplate(factory.getObject());
        for (var type :
                List.of(
                        CommunityMapper.class,
                        PickupPointMapper.class,
                        UserMapper.class,
                        MchAccountMapper.class,
                        MchEntityMapper.class,
                        QualificationMapper.class,
                        MchPaymentMapper.class,
                        ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper.class,
                        EntityPlanMapper.class,
                        PlanDefMapper.class,
                        ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper.class,
                        MchStoreMapper.class,
                        MchStoreRoleMapper.class,
                        MchRoleMapper.class,
                        ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper.class,
                        ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMapper.class,
                        ai.neargo.shop.platform.perm.mapper.PermMappers.RolePointMapper.class,
                        ai.neargo.shop.platform.perm.mapper.PermMappers.FunctionPointMapper.class)) cfg.addMapper(type);
        loader =
                new CompositeIdentityLoader<>(
                        new MerchantIdentityLoader(mybatis.getMapper(MchAccountMapper.class)),
                        new ConsumerIdentityLoader(mybatis.getMapper(UserMapper.class)));
        var profile = SessionProfiles.MERCHANT;
        dao = new SessionDao(JdbcClient.create(ds), profile);
        sc =
                new AuthCache<>(
                        schema + "s",
                        String.class,
                        DbTokenStore.CachedSession.class,
                        Duration.ofMinutes(10),
                        100);
        ic =
                new AuthCache<>(
                        schema + "i", String.class, LoginUser.class, Duration.ofMinutes(10), 100);
        audit = new LoginLogWriter(new LoginLogDao(JdbcClient.create(ds), profile), profile);
        store = new DbTokenStore(Realm.MERCHANT, profile, dao, loader, sc, ic, audit, clock);
    }

    public void close() {
        audit.close();
        sc.close();
        ic.close();
        admin.execute("DROP DATABASE " + schema);
    }
}
