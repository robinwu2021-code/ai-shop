package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.service.impl.ActivityPricingServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** V644 on MySQL 9 plus real mapper/service races; no application or existing business database is changed. */
@EnabledIfEnvironmentVariable(named = "R01_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailItemBenefitNativeMysqlTest {
    private final String schema = "retail_r01_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ActivityPricingServiceImpl pricing;

    @BeforeAll
    void database() throws Exception {
        String url = System.getenv("R01_MYSQL_URL");
        String user = System.getenv("R01_MYSQL_USER");
        String password = System.getenv("R01_MYSQL_PASSWORD");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3308/information_schema");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var source = new DriverManagerDataSource(url.replace("information_schema", schema), user, password);
        jdbc = new JdbcTemplate(source);
        String ddl = new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String table : List.of("ord_item", "pmt_apply", "pmt_activity")) {
            var matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?\n\\);")
                    .matcher(ddl);
            assertThat(matcher.find()).as(table).isTrue();
            String predecessor = matcher.group().replaceAll(
                    "(?m)^    (activity_no|activity_benefit_type|activity_benefit_minor|budget_minor) [^\n]*\n", "");
            // Only ord_item's activity_no and pmt_apply's budget_minor are new; activity roots keep theirs.
            if (table.equals("pmt_activity")) predecessor = matcher.group();
            jdbc.execute(predecessor);
        }
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V644__retail_item_benefit_snapshot.sql")).execute(source);
        jdbc.execute("CREATE INDEX idx_pmt_apply_order ON pmt_apply(order_no)");
        jdbc.execute("CREATE INDEX idx_pmt_apply_promo ON pmt_apply(promo_type,promo_no,applied_at)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(ActivityMapper.class);
        sessions.getConfiguration().addMapper(ApplyMapper.class);
        var template = new SqlSessionTemplate(sessions);
        pricing = new ActivityPricingServiceImpl(template.getMapper(ActivityMapper.class), null, null, null,
                template.getMapper(ApplyMapper.class));
    }

    @AfterAll
    void dropDatabase() {
        if (admin != null) admin.execute("DROP DATABASE IF EXISTS " + schema);
    }

    @Test
    void concurrentSameOrderAndReplayAfterCancellationChargeOnlyOnce() throws Exception {
        activity("ACT-R01-SAME", 50, 10_000);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(() -> {
                go.await();
                return transaction.execute(s ->
                        pricing.commitItemUsage("U", "ORDER-SAME", "ACT-R01-SAME", "M", 1, 300));
            }));
            go.countDown();
            for (var result : results) assertThat(result.get(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(value("quota_used", "ACT-R01-SAME")).isEqualTo(1);
        assertThat(value("budget_used_minor", "ACT-R01-SAME")).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE order_no='ORDER-SAME'", Long.class))
                .isEqualTo(1);
        transaction.executeWithoutResult(s -> pricing.release("ORDER-SAME"));
        transaction.executeWithoutResult(s -> pricing.commitItemUsage("U", "ORDER-SAME", "ACT-R01-SAME", "M", 1, 300));
        assertThat(value("quota_used", "ACT-R01-SAME")).isZero();
        assertThat(value("budget_used_minor", "ACT-R01-SAME")).isZero();
    }

    @Test
    void concurrentLastBudgetAndOverrunCancellationNeverAffectTheWinner() throws Exception {
        activity("ACT-R01-LAST", 50, 450);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                go.await();
                return transaction.execute(s -> pricing.commitItemUsage("U", "ORDER-A", "ACT-R01-LAST", "M", 1, 300));
            });
            var second = pool.submit(() -> {
                go.await();
                return transaction.execute(s -> pricing.commitItemUsage("U", "ORDER-B", "ACT-R01-LAST", "M", 1, 300));
            });
            go.countDown();
            boolean a = first.get(15, TimeUnit.SECONDS);
            boolean b = second.get(15, TimeUnit.SECONDS);
            assertThat(a ^ b).isTrue();
            transaction.executeWithoutResult(s -> pricing.release(a ? "ORDER-B" : "ORDER-A"));
        }
        assertThat(value("quota_used", "ACT-R01-LAST")).isEqualTo(1);
        assertThat(value("budget_used_minor", "ACT-R01-LAST")).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT SUM(budget_minor) FROM pmt_apply WHERE promo_no='ACT-R01-LAST'",
                Long.class)).isEqualTo(300);
    }

    @Test
    void concurrentDifferentActivitiesDoNotLockEachOthersReceiptGaps() throws Exception {
        int parallel = 8;
        for (int i = 0; i < parallel; i++) activity("ACT-R01-GAP-" + i, 50, 10_000);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(parallel)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < parallel; i++) {
                int item = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return transaction.execute(s -> pricing.commitItemUsage(
                            "U", "ORDER-GAP-" + item, "ACT-R01-GAP-" + item, "M", 1, 100));
                }));
            }
            go.countDown();
            for (var result : results) assertThat(result.get(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE order_no LIKE 'ORDER-GAP-%'",
                Long.class)).isEqualTo(parallel);
    }

    private void activity(String no, int quota, long budget) {
        jdbc.update("INSERT INTO pmt_activity(activity_no,entity_no,name,trigger_type,benefit_type,status,"
                + "quota,quota_used,"
                + "budget_minor,budget_used_minor,created_at,updated_at) VALUES(?, 'M', 'R01', 'GOODS', 'PRICE',"
                + "'RUNNING',?,0,?,0,NOW(),NOW())", no, quota, budget);
    }

    private long value(String field, String no) {
        return jdbc.queryForObject("SELECT " + field + " FROM pmt_activity WHERE activity_no=?", Long.class, no);
    }
}
