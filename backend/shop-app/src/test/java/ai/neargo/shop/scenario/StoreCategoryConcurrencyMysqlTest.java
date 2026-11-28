package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreCategory;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreCategoryMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.StoreCategoryService.Item;
import ai.neargo.shop.merchant.service.StoreCategoryService.StoreCategoryVO;
import ai.neargo.shop.merchant.service.impl.StoreCategoryServiceImpl;
import ai.neargo.shop.spi.product.CategoryUsagePort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "C06_REVIEW_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class StoreCategoryConcurrencyMysqlTest {
    @Test
    void identicalFirstSelectionsReturnTheSameShelfAndForeignKeyCollisionIsConflict() throws Exception {
        String schema = "store_cat_race_" + UUID.randomUUID().toString().replace("-", "");
        String user = System.getenv("C06_REVIEW_MYSQL_USER");
        String password = System.getenv("C06_REVIEW_MYSQL_PASSWORD");
        String url = "jdbc:mysql://127.0.0.1:3308/";
        var admin = new JdbcTemplate(new DriverManagerDataSource(url + "information_schema", user, password));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(url + schema, user, password);
            var jdbc = new JdbcTemplate(source);
            String ddl = new ClassPathResource("schema-product-test.sql").getContentAsString(StandardCharsets.UTF_8);
            var match = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS prd_store_category \\(.*?\n\\);").matcher(ddl);
            assertThat(match.find()).isTrue();
            jdbc.execute(match.group());
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(config);
            factory.setDataSource(source);
            var interceptors = new MybatisPlusInterceptor();
            interceptors.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptors);
            factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
            var sessions = factory.getObject();
            sessions.getConfiguration().addMapper(MchStoreMapper.class);
            sessions.getConfiguration().addMapper(MchEntityMapper.class);
            sessions.getConfiguration().addMapper(MchStoreCategoryMapper.class);
            var mapper = new SqlSessionTemplate(sessions).getMapper(MchStoreCategoryMapper.class);
            var categories = mock(CategoryUsagePort.class);
            when(categories.isActive(any())).thenReturn(true);
            var stores = mock(MchStoreMapper.class);
            when(stores.selectOne(any())).thenReturn(new MchStore());
            var entities = mock(MchEntityMapper.class);
            when(entities.selectOne(any())).thenReturn(new MchEntity());
            var service = new StoreCategoryServiceImpl(mapper, categories, null, null, stores, entities);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var ready = new CyclicBarrier(2);
            Callable<List<StoreCategoryVO>> select = () -> tx.execute(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_store_category", Integer.class)).isZero();
                try {
                    ready.await(5, TimeUnit.SECONDS);
                } catch (Exception interrupted) {
                    throw new AssertionError(interrupted);
                }
                return service.replace("ENTITY", "ST", List.of(new Item("CAT", "货架", 3)));
            });
            try (var threads = Executors.newFixedThreadPool(2)) {
                var first = threads.submit(select);
                var second = threads.submit(select);
                var result = first.get(10, TimeUnit.SECONDS);
                assertThat(result).extracting(StoreCategoryVO::name).containsExactly("货架");
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(result);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_store_category", Integer.class)).isOne();
            String key = MchStoreCategory.rowNo("ST", "OTHER");
            jdbc.update("""
                    INSERT INTO prd_store_category(tenant_no,entity_no,store_no,category_no,catalog_no,name)
                    VALUES ('MAIN','FOREIGN','ST',?,'MENU','保留')
                    """, key);
            assertThatThrownBy(() -> tx.execute(status ->
                    service.replace("ENTITY", "ST", List.of(new Item("CAT", "货架", 3), new Item("OTHER", "", 0)))))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(jdbc.queryForObject("SELECT name FROM prd_store_category WHERE category_no=?", String.class, key))
                    .isEqualTo("保留");
            tx.execute(status -> service.replace("ENTITY", "ST", List.of()));
            var restored = tx.execute(status -> service.replace("ENTITY", "ST", List.of(new Item("CAT", "恢复", 1))));
            assertThat(restored).extracting(StoreCategoryVO::name).containsExactly("恢复");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_store_category WHERE entity_no='ENTITY'",
                    Integer.class)).isOne();
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
