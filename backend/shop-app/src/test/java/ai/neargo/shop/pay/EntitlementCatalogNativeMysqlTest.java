package ai.neargo.shop.pay;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementCatalogServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateVersionMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntCommandDedupMapper;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in native service verification; the runner supplies a fresh loopback-only disposable database. */
@EnabledIfEnvironmentVariable(named = "ENTITLEMENT_NATIVE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class EntitlementCatalogNativeMysqlTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
    }

    @Test
    void realMysqlPreservesSnapshotsConcurrentIdempotencyAndRollback() throws Exception {
        String url = System.getenv("ENTITLEMENT_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/entitlement_native_fixture\\?.*");
        var ds = new DriverManagerDataSource(url, "root", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V425__entitlement_catalog.sql")).execute(ds);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setPlugins(interceptor);
        SqlSessionFactory sessions = factory.getObject();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class, EntitlementCatalogServiceImpl.class);
            context.registerBean("payTxManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(ds));
            context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
            registerMapper(context, EntTemplateMapper.class, sessions);
            registerMapper(context, EntTemplateVersionMapper.class, sessions);
            registerMapper(context, PayEntCommandDedupMapper.class, sessions);
            context.refresh();
            var catalog = context.getBean(EntitlementCatalogService.class);
            var scope = new CatalogScope("MAIN", "MYSQL-" + UUID.randomUUID(), "NATIVE_TEST", false);
            var definition = new EntitlementDefinition("Native coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH",
                    500L, null, null, 0L, null, "COUNT", 1, null,
                    new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                    new RedemptionScope("ALL", List.of()), List.of("ACTIVITY"));
            String key = UUID.randomUUID().toString();
            try (var executor = Executors.newFixedThreadPool(4)) {
                var tasks = new ArrayList<Callable<String>>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> catalog.create(scope, definition, key).templateNo());
                }
                var ids = new ArrayList<String>();
                for (var result : executor.invokeAll(tasks, 20, TimeUnit.SECONDS)) {
                    ids.add(result.get());
                }
                assertThat(ids.stream().distinct().count()).isEqualTo(1);
            }
            var page = catalog.list(scope, null, null, 1, 20);
            assertThat(page.total()).isEqualTo(1);
            var head = page.records().getFirst();
            var published = catalog.publish(scope, head.templateNo(), 0, UUID.randomUUID().toString());
            assertThat(catalog.getVersion(scope, published.versionNo())).isEqualTo(published);
            assertThat(catalog.create(scope, definition, key).revision()).isZero();
            String rollback = UUID.randomUUID().toString();
            var manager = context.getBean("payTxManager", PlatformTransactionManager.class);
            assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
                catalog.create(scope, definition, rollback);
                throw new IllegalStateException("abort outer pay transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(catalog.list(scope, null, null, 1, 20).total()).isEqualTo(1);
            var jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE request_key=?",
                    Long.class, rollback)).isZero();
            assertThat(catalog.create(scope, definition, rollback).revision()).isZero();
        }
    }

    private <T> void registerMapper(AnnotationConfigApplicationContext context, Class<T> type,
                                    SqlSessionFactory sessions) {
        context.registerBean(type.getSimpleName(), MapperFactoryBean.class, () -> {
            var mapper = new MapperFactoryBean<>(type);
            mapper.setSqlSessionFactory(sessions);
            return mapper;
        });
    }
}
