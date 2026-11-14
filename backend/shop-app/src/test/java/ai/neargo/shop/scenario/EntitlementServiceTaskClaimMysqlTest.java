package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.mapper.EntitlementServiceOrderMapper;
import ai.neargo.shop.trade.mapper.EntitlementServiceOrderMapper.TaskClaim;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact migration and mapper on MySQL; each run owns and removes its own schema. */
@EnabledIfEnvironmentVariable(named = "B07_TASK_MYSQL_URL", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/information_schema.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntitlementServiceTaskClaimMysqlTest {
    private final String schema = "b07_task_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private EntitlementServiceOrderMapper orders;
    private TransactionTemplate transaction;

    @BeforeAll
    void database() throws Exception {
        String url = System.getenv("B07_TASK_MYSQL_URL");
        String user = System.getenv("B07_TASK_MYSQL_USER");
        String password = System.getenv("B07_TASK_MYSQL_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var source = new DriverManagerDataSource(url.replace("/information_schema", "/" + schema), user, password);
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V495__entitlement_service_order.sql"),
                new ClassPathResource("db/migration/V670__service_work_order_task_claim.sql")).execute(source);

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(EntitlementServiceOrderMapper.class);
        orders = new SqlSessionTemplate(sessions).getMapper(EntitlementServiceOrderMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @AfterAll
    void cleanup() {
        if (admin != null) admin.execute("DROP DATABASE IF EXISTS " + schema);
    }

    @Test
    void concurrentKeysSelectOnlyOneOriginalForTheTask() throws Exception {
        var claims = List.of("RACE1", "RACE2", "RACE3", "RACE4").stream().map(number -> {
            seed(number);
            return new TaskClaim("MAIN", "ENTITY", "WO", "RACE", number);
        }).toList();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var attempts = claims.stream().map(claim -> executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return claim(claim);
            })).toList();
            start.countDown();
            int winners = 0;
            for (var attempt : attempts) {
                if (attempt.get(10, TimeUnit.SECONDS)) winners++;
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_ent_service_task_claim "
                + "WHERE task_no='RACE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancellationDecisionRetainsClaimUntilOriginalReleaseIsAcknowledged() {
        seed("OLD");
        seed("NEW");
        var original = new TaskClaim("MAIN", "ENTITY", "WO", "RELEASE", "OLD");
        var next = new TaskClaim("MAIN", "ENTITY", "WO", "RELEASE", "NEW");
        assertThat(claim(original)).isTrue();
        assertThat(claim(original)).isTrue();
        jdbc.update("UPDATE ord_ent_service_order SET status='CANCEL_DECIDED',decision_snapshot='{}' "
                + "WHERE service_order_no='OLD'");
        assertThat(claim(next)).isFalse();
        jdbc.update("UPDATE ord_ent_service_order SET status='RELEASED' WHERE service_order_no='OLD'");
        assertThat(claim(next)).isTrue();
        assertThat(claim(original)).isFalse();
    }

    private boolean claim(TaskClaim claim) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            orders.insertTaskClaim(claim);
            orders.replaceReleasedTaskClaim(claim);
            return claim.serviceOrderNo().equals(orders.lockTaskClaim(claim));
        }));
    }

    private void seed(String number) {
        jdbc.update("""
                INSERT INTO ord_ent_service_order(tenant_no,service_order_no,operation_no,entity_no,store_no,
                  user_no,operator_no,request_key_hash,payload_hash,source_snapshot,source_hash,status,
                  context_snapshot,deadline,created_at,updated_at,created_by,updated_by)
                VALUES ('MAIN',?,?,'ENTITY','STORE','MEMBER','ACTOR',?,?,'{}',?,'CREATED','{}',
                  CURRENT_TIMESTAMP+INTERVAL 15 MINUTE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'ACTOR','ACTOR')
                """, number, number, number, "a".repeat(64), "b".repeat(64));
    }
}
