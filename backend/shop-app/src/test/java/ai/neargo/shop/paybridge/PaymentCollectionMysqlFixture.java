package ai.neargo.shop.paybridge;

import static org.mockito.Mockito.mock;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.event.*;
import ai.neargo.shop.event.outbox.runtime.*;
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.merchant.service.impl.BizIdentityResolverImpl;
import ai.neargo.shop.message.impl.MessageServiceImpl;
import ai.neargo.shop.message.mapper.MessageMappers.MessageMapper;
import ai.neargo.shop.paybridge.impl.PaymentCollectionServiceImpl;
import ai.neargo.shop.spi.user.PickupQueryPort;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Disposable MySQL9 only. Production schemas and production credentials are never a target. */
final class PaymentCollectionMysqlFixture implements AutoCloseable {
    final String schema = "payment_sse_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource ds;
    final JdbcTemplate admin, jdbc;
    final DataSourceTransactionManager manager;
    final ObjectMapper json = new ObjectMapper();
    final PaymentCollectionProperties properties = new PaymentCollectionProperties();
    final PaymentCollectionSecurity security;
    final String configuredToken = System.getenv("PAYMENT_SSE_TOKEN");
    final MemoryTokenStore tokens =
            new MemoryTokenStore(Duration.ofHours(2)) {
                private String actual;

                @Override
                public String issue(TokenStore.SessionData data) {
                    actual = super.issue(data);
                    return configuredToken == null ? actual : configuredToken;
                }

                @Override
                public Optional<TokenStore.SessionData> get(String token) {
                    return super.get(
                            configuredToken != null && configuredToken.equals(token)
                                    ? actual
                                    : token);
                }

                @Override
                public void revoke(String token) {
                    super.revoke(
                            configuredToken != null && configuredToken.equals(token)
                                    ? actual
                                    : token);
                }
            };
    final LoginUser user = LoginUser.merchantByUser("USR_SSE_SANDBOX", "sandbox");
    final String token;
    final BizIdentityResolver identities;
    final MessageServiceImpl messages;
    final PaymentCollectionProjection projection;
    final UnifiedOutboxFacade facade;
    final OutboxRunner runner;
    final PaymentCollectionService service;
    final PaymentCollectionStream stream;

    PaymentCollectionMysqlFixture() throws Exception {
        String url = System.getenv("PAYMENT_SSE_JDBC_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*"))
            throw new IllegalArgumentException(
                    "Only local MySQL9 admin URL jdbc:mysql://127.0.0.1:3308/?... is allowed");
        String dbUser = System.getenv("PAYMENT_SSE_DB_USER"),
                password = System.getenv("PAYMENT_SSE_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, dbUser, password));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        if (!version.startsWith("9.")) throw new IllegalArgumentException("MySQL9 required");
        admin.execute("CREATE DATABASE " + schema);
        ds = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), dbUser, password);
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        String ddl =
                new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String table :
                List.of(
                        "mch_entity",
                        "mch_account",
                        "mch_store",
                        "mch_store_role",
                        "mch_role",
                        "notify_message",
                        "sys_outbox",
                        "sys_outbox_stream",
                        "sys_outbox_delivery",
                        "sys_outbox_operation",
                        "sys_event_consumed")) {
            var match =
                    Pattern.compile(
                                    "CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?;",
                                    Pattern.DOTALL)
                            .matcher(ddl);
            if (!match.find()) throw new IllegalStateException("missing test DDL " + table);
            String create = match.group();
            if (table.equals("notify_message"))
                create =
                        create.replaceAll(
                                "(?m)^    (biz_type|biz_no|store_no|entity_no) VARCHAR[^\\n]*\\n",
                                "");
            jdbc.execute(create);
        }
        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/V664__sandbox_payment_collection_stream.sql"))
                .execute(ds);
        jdbc.update(
                "INSERT INTO mch_entity(entity_no,name,market,status,created_at,updated_at) VALUES"
                        + " ('ENT_SSE_SANDBOX','sandbox','TW','ACTIVE',now(),now())");
        jdbc.update(
                "INSERT INTO mch_store(id,entity_no,store_no,name,created_at,updated_at) VALUES"
                    + " (101,'ENT_SSE_SANDBOX','ST_SSE_SANDBOX','sandbox',now(),now()),(102,'ENT_SSE_SANDBOX','ST_SSE_OTHER','other',now(),now())");
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(mch_account_no,user_no,entity_no,is_owner,is_primary,status,created_at,updated_at)"
                    + " VALUES"
                    + " ('MCH_SSE_SANDBOX','USR_SSE_SANDBOX','ENT_SSE_SANDBOX',1,1,'ACTIVE',now(),now()),('MCH_CLERK','USR_CLERK','ENT_SSE_SANDBOX',0,1,'ACTIVE',now(),now()),('MCH_PICKER','USR_PICKER','ENT_SSE_SANDBOX',0,1,'ACTIVE',now(),now())");
        jdbc.update(
                "INSERT INTO mch_role(role_code,entity_no,name,perms,created_at,updated_at) VALUES"
                    + " ('CLERK','*','clerk','[\"mch:pay:order:view\"]',now(),now()),('PICKER','*','picker','[\"biz:stock\"]',now(),now())");
        jdbc.update(
                "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                    + " VALUES"
                    + " ('MCH_CLERK','ST_SSE_SANDBOX','CLERK',now(),now()),('MCH_PICKER','ST_SSE_SANDBOX','PICKER',now(),now())");
        jdbc.update(
                "INSERT INTO"
                    + " payment_collection_source(environment,pay_app_id,source_type,source_id,payment_store_id,store_no,entity_no)"
                    + " VALUES"
                    + " ('SANDBOX',42,'CHECKOUT',42,9001,'ST_SSE_SANDBOX','ENT_SSE_SANDBOX'),('SANDBOX',42,'BIZ',42,9001,'ST_SSE_SANDBOX','ENT_SSE_SANDBOX')");
        var config = new MybatisConfiguration();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setDataSource(ds);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(plugins);
        factory.setGlobalConfig(
                new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
        var session = new SqlSessionTemplate(factory.getObject());
        for (var mapper :
                List.of(
                        MchEntityMapper.class,
                        MchAccountMapper.class,
                        MchStoreMapper.class,
                        MchStoreRoleMapper.class,
                        MchRoleMapper.class,
                        MessageMapper.class,
                        SysOutboxMapper.class)) config.addMapper(mapper);
        identities =
                new BizIdentityResolverImpl(
                        session.getMapper(MchEntityMapper.class),
                        mock(PickupQueryPort.class),
                        session.getMapper(MchAccountMapper.class),
                        session.getMapper(MchStoreMapper.class),
                        session.getMapper(MchStoreRoleMapper.class),
                        session.getMapper(MchRoleMapper.class));
        messages =
                new MessageServiceImpl(
                        session.getMapper(MessageMapper.class),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        json);
        properties.setEnabled(true);
        properties.setSecret(env("PAYMENT_SSE_SECRET", "sandbox-only-test-secret-32-characters"));
        properties.setCursorSecret(
                env("PAYMENT_SSE_CURSOR_SECRET", "sandbox-only-test-cursor-secret-32-characters"));
        properties.setPollMs(100);
        properties.setHeartbeatMs(1000);
        security = new PaymentCollectionSecurity(properties, Clock.systemUTC());
        token = tokens.issue(TokenStore.SessionData.of(user));
        var markets = mock(ai.neargo.shop.spi.pay.MarketPort.class);
        org.mockito.Mockito.when(markets.find("TW"))
                .thenReturn(
                        Optional.of(
                                new ai.neargo.shop.spi.pay.MarketPort.MarketBrief(
                                        "TW", "Taiwan", "TWD", 2, "Asia/Taipei", 1, true)));
        projection =
                new PaymentCollectionProjection(
                        ds, manager, json, messages, identities, markets, Clock.systemUTC());
        facade = new UnifiedOutboxFacade(json);
        facade.register(
                new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, ds, manager, json),
                List.of(
                        new TransactionalOutboxHandler(
                                projection, ds, manager, "sys_event_consumed")));
        var bus =
                new OutboxEventBus(
                        session.getMapper(SysOutboxMapper.class),
                        json,
                        new StaticListableBeanFactory().getBeanProvider(OutboxPump.class));
        bus.setUnified(facade);
        service = new PaymentCollectionServiceImpl(ds, manager, bus);
        var props = new OutboxProperties();
        props.setRecoveryInterval(Duration.ofMillis(100));
        runner = new OutboxRunner(facade, Clock.systemUTC(), props);
        stream =
                new PaymentCollectionStream(
                        ds,
                        properties,
                        security,
                        identities,
                        tokens,
                        projection,
                        Clock.systemUTC());
    }

    static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    PaymentCollectionFact fact(long id) {
        return new PaymentCollectionFact(
                1,
                2,
                "PAYMENT_SUCCEEDED|O" + id + "|2",
                "PAYMENT_SUCCEEDED",
                42L,
                "CHECKOUT",
                42L,
                "P" + id,
                "OUT" + id,
                "CHECKOUT_QR",
                "QR" + id,
                9001L,
                "TW",
                "TWD",
                100,
                100,
                "PM" + id,
                "OT" + id,
                "SANDBOX",
                "CH" + id,
                System.currentTimeMillis());
    }

    PaymentCollectionService.Accepted receive(long id) {
        var fact = fact(id);
        return service.receive(fact, json.writeValueAsString(fact));
    }

    SysOutbox work(String receipt) {
        SysOutbox work = new SysOutbox();
        work.setEventNo("work" + receipt);
        work.setEventType(PaymentCollectionWork.TYPE);
        work.setPayload(json.writeValueAsString(new PaymentCollectionWork(receipt)));
        return work;
    }

    @Override
    public void close() {
        stream.close();
        runner.close();
        admin.execute("DROP DATABASE " + schema);
    }
}
