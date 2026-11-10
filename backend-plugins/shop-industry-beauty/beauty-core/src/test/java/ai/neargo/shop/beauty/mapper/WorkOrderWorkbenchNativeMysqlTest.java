package ai.neargo.shop.beauty.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

/** Disposable local MySQL9 only; inherits exactly the H2 SQL behavior assertions. */
@EnabledIfSystemProperty(named = "svc.b02.native", matches = "true")
class WorkOrderWorkbenchNativeMysqlTest extends WorkOrderWorkbenchSqlTest {
    private JdbcTemplate admin;
    private String catalog;

    @Override
    protected DriverManagerDataSource dataSource() {
        String url = System.getenv("SVC_B02_NATIVE_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3308/");
        var source =
                new DriverManagerDataSource(
                        url,
                        System.getenv("SVC_B02_NATIVE_USER"),
                        System.getenv("SVC_B02_NATIVE_PASSWORD"));
        admin = new JdbcTemplate(source);
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        catalog = "food_guard_it_svc_b02_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + catalog);
        return new DriverManagerDataSource(
                url.substring(0, url.lastIndexOf('/') + 1) + catalog,
                System.getenv("SVC_B02_NATIVE_USER"),
                System.getenv("SVC_B02_NATIVE_PASSWORD"));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && catalog != null) admin.execute("DROP DATABASE " + catalog);
    }
}
