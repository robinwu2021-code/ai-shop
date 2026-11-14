package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

class IsolatedH2ContextCustomizerFactoryTest {
    @Test void onlyNamedInMemoryDatabasesAreRewrittenAndOptionsArePreserved() {
        String original = "jdbc:h2:mem:shop;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        assertThat(IsolatedH2ContextCustomizerFactory.isolateUrl(original, "one"))
                .isEqualTo("jdbc:h2:mem:shop_ctx_one;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0");
        for (String url : List.of("jdbc:mysql://localhost/native", "jdbc:mariadb://localhost/native",
                "jdbc:h2:file:/tmp/external-pay;AUTO_SERVER=TRUE", "jdbc:h2:mem:")) {
            assertThat(IsolatedH2ContextCustomizerFactory.isolateUrl(url, "one")).isEqualTo(url);
        }
        var factory = new IsolatedH2ContextCustomizerFactory();
        assertThat(factory.createContextCustomizer(First.class, List.of()))
                .isEqualTo(factory.createContextCustomizer(Second.class, List.of()))
                .hasSameHashCodeAs(factory.createContextCustomizer(Second.class, List.of()));
    }

    @Test void contextsKeepPooledStateAliveWithoutSharingAndDropItWhenClosed() throws Exception {
        var first = new TestContextManager(First.class).getTestContext();
        var second = new TestContextManager(Second.class).getTestContext();
        String oldUrl;
        try {
            var a = first.getApplicationContext();
            var b = second.getApplicationContext();
            var source = a.getBean(HikariDataSource.class);
            oldUrl = source.getJdbcUrl();
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE evidence(id INT PRIMARY KEY, mode VARCHAR CHECK(mode IN ('A','B')))");
            jdbc.update("INSERT INTO evidence VALUES (1,'A')");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class)).isOne();
            assertThat(b.getBean(HikariDataSource.class).getJdbcUrl()).isNotEqualTo(oldUrl);
            assertThat(a.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo(a.getEnvironment().getProperty("shop.inventory.datasource.url"));
            assertThat(a.getEnvironment().getProperty("shop.job.datasource.url"))
                    .isNotEqualTo(oldUrl).contains("job_ctx_");
            assertThat(a.getEnvironment().getProperty("points.source.jdbc.url"))
                    .isEqualTo("jdbc:h2:mem:raw_fixture;DB_CLOSE_DELAY=-1");
            assertThatThrownBy(() -> new JdbcTemplate(b.getBean(HikariDataSource.class))
                    .queryForObject("SELECT COUNT(*) FROM evidence", Integer.class))
                    .rootCause().hasMessageContaining("EVIDENCE");
            first.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
            assertThat(source.isClosed()).isTrue();
            try (var connection = DriverManager.getConnection(oldUrl, "sa", "")) {
                assertThatThrownBy(() -> connection.createStatement().executeQuery("SELECT * FROM evidence"))
                        .hasMessageContaining("EVIDENCE");
            }
            var reloaded = first.getApplicationContext();
            assertThat(reloaded.getBean(HikariDataSource.class).getJdbcUrl()).isNotEqualTo(oldUrl);
            var fresh = new JdbcTemplate(reloaded.getBean(HikariDataSource.class));
            fresh.execute("CREATE TABLE evidence(mode VARCHAR CHECK(mode IN ('A','B')))");
            fresh.update("INSERT INTO evidence VALUES ('B')");
            assertThat(fresh.queryForObject("SELECT mode FROM evidence", String.class)).isEqualTo("B");
        } finally {
            first.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
            second.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
        }
    }

    @SpringJUnitConfig(Pool.class)
    @TestPropertySource(properties = {"context.marker=first", "spring.datasource.url=jdbc:h2:mem:shared_fixture;DB_CLOSE_DELAY=-1",
            "shop.inventory.datasource.url=jdbc:h2:mem:shared_fixture;DB_CLOSE_DELAY=-1",
            "shop.job.datasource.url=jdbc:h2:mem:job;DB_CLOSE_DELAY=-1",
            "points.source.jdbc.url=jdbc:h2:mem:raw_fixture;DB_CLOSE_DELAY=-1"})
    static class First {}

    @SpringJUnitConfig(Pool.class)
    @TestPropertySource(properties = {"context.marker=second", "spring.datasource.url=jdbc:h2:mem:shared_fixture;DB_CLOSE_DELAY=-1"})
    static class Second {}

    @Configuration(proxyBeanMethods = false)
    static class Pool {
        @Bean(destroyMethod = "close") HikariDataSource dataSource(Environment environment) {
            var pool = new HikariDataSource();
            pool.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
            pool.setUsername("sa"); pool.setPassword("");
            pool.setMaximumPoolSize(1); pool.setMinimumIdle(1);
            return pool;
        }
    }
}
