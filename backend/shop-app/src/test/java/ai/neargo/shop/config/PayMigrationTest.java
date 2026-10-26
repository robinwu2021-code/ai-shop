package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 支付 v4 迁移走独立目录与独立历史表（TDD-支付服务-I0地基 AC2）。
 *
 * <p>不起 Spring：要验的是「记到哪张历史表、碰没碰平台那张」，起整个单体只会把这件事淹没。
 * 「平台 Flyway 不会因此退让」由 {@code ArchitectureTest#noBeanMayExposeFlyway} 静态守着 ——
 * {@code payFlyway} 返回的是 {@code PayMigrated}，不是 {@code Flyway}。
 */
class PayMigrationTest {

    @Test
    @DisplayName("★★★ 支付迁移记在 pay_flyway_history，平台的 flyway_schema_history 一行都不碰")
    void appliesIntoOwnHistoryTable() throws SQLException {
        JdbcDataSource ds = h2("pay_mig_own");

        var first = PayDataSourceConfig.migratePay(ds, true, "classpath:db/pay-fixture");

        assertThat(first.historyTable()).isEqualTo("pay_flyway_history");
        assertThat(first.applied()).isEqualTo(1);
        assertThat(tableExists(ds, "pay_flyway_history")).isTrue();
        assertThat(tableExists(ds, "pay_fixture_probe")).isTrue();
        assertThat(tableExists(ds, "flyway_schema_history"))
                .as("支付迁移写进了平台的历史表 —— 两边的迁移号会互相撞")
                .isFalse();

        var second = PayDataSourceConfig.migratePay(ds, true, "classpath:db/pay-fixture");
        assertThat(second.applied()).as("第二次启动不该重跑").isZero();
    }

    @Test
    @DisplayName("★★ 真目录 db/pay 只放按版本命名的 SQL，号从 1 起连续 —— 支付迁移不与平台抢号，也不跳号")
    void realLocationIsVersionedFromOne() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var versions = new java.util.ArrayList<Integer>();
        for (var r : resolver.getResources("classpath:db/pay/*.sql")) {
            var m = java.util.regex.Pattern.compile("V(\\d+)__\\w+\\.sql").matcher(r.getFilename());
            assertThat(m.matches()).as("不规范的文件名：%s", r.getFilename()).isTrue();
            versions.add(Integer.parseInt(m.group(1)));
        }
        java.util.Collections.sort(versions);
        assertThat(versions).as("db/pay 一条迁移都没有").isNotEmpty();
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).isEqualTo(i + 1);
        }
        // 真迁移要 ALTER 平台表，空库上跑不了 —— 在 MySQL 9.7 上的执行验证见 TDD-支付服务-I1存量缺陷 §7
    }

    @Test
    @DisplayName("★ 关掉开关就一张表都不建 —— H2 测试走 schema-test.sql，不能被它插一脚")
    void disabledTouchesNothing() throws SQLException {
        JdbcDataSource ds = h2("pay_mig_off");

        var result = PayDataSourceConfig.migratePay(ds, false, "classpath:db/pay-fixture");

        assertThat(result.applied()).isZero();
        assertThat(tableExists(ds, "pay_flyway_history")).isFalse();
        assertThat(tableExists(ds, "pay_fixture_probe")).isFalse();
    }

    private static JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }

    private static boolean tableExists(JdbcDataSource ds, String table) throws SQLException {
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, null, table, new String[] {"TABLE"})) {
            return rs.next();
        }
    }
}
