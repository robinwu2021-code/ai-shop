package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 密钥模块迁移走独立目录 {@code db/secret} 与独立历史表 {@code sec_flyway_history}（TDD-支付服务-W5密钥模块 AC10）。
 *
 * <p>与 {@code PayMigrationTest} 不同，这里跑的是<b>真迁移</b>：{@code sec_*} 不 ALTER 任何平台表，空库上就能跑。
 * 第二个用例在给了 {@code -Dsecret.mysql.url}（及 {@code .user} / {@code .password}）时对真 MySQL 跑一遍 ——
 * H2 不执行 MySQL 的 COMMENT / CHECK 语义，方言问题只有真库看得见。指向一个<b>空的临时库</b>，不要指向开发库。
 */
class SecretMigrationTest {

    @Test
    @DisplayName("★★★ db/secret 记在 sec_flyway_history，七张表齐，平台与支付的历史表一张都不碰；第二次启动不重跑")
    void appliesIntoOwnHistoryTable() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:secret_mig;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");

        assertMigratesCleanly(ds);
    }

    @Test
    @DisplayName("★★ 同一份迁移在真 MySQL 上跑通（给了 -Dsecret.mysql.url 才跑）")
    void appliesOnRealMysql() throws SQLException {
        String url = System.getProperty("secret.mysql.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no -Dsecret.mysql.url");
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(url);
            ds.setUsername(System.getProperty("secret.mysql.user", "root"));
            ds.setPassword(System.getProperty("secret.mysql.password", ""));
            assertMigratesCleanly(ds);
        }
    }

    @Test
    @DisplayName("★ 关掉开关就一张表都不建")
    void disabledTouchesNothing() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:secret_mig_off;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");

        assertThat(SecretDataSourceConfig.migrateSecret(ds, false, "classpath:db/secret").applied()).isZero();
        assertThat(tables(ds)).isEmpty();
    }

    private static void assertMigratesCleanly(DataSource ds) throws SQLException {
        var first = SecretDataSourceConfig.migrateSecret(ds, true, "classpath:db/secret");

        assertThat(first.historyTable()).isEqualTo("sec_flyway_history");
        assertThat(first.applied()).isEqualTo(1);
        assertThat(tables(ds)).containsExactlyInAnyOrder("sec_flyway_history", "sec_master_key", "sec_secret_blob",
                "sec_key_policy", "sec_key", "sec_key_version", "sec_key_event", "sec_access_log");
        assertThat(SecretDataSourceConfig.migrateSecret(ds, true, "classpath:db/secret").applied())
                .as("第二次启动不该重跑").isZero();
    }

    private static List<String> tables(DataSource ds) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String t = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (t.startsWith("sec_") || t.contains("flyway")) {
                    out.add(t);
                }
            }
        }
        return out;
    }
}
