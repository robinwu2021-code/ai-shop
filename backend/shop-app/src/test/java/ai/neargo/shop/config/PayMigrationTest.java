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
    @DisplayName("★★ 真目录 db/pay 今天为空：只建历史表、零迁移、不报错 —— 生产首次部署就是这个样子")
    void realLocationIsEmptyButValid() throws SQLException {
        JdbcDataSource ds = h2("pay_mig_real");

        var result = PayDataSourceConfig.migratePay(ds, true, "classpath:db/pay");

        assertThat(result.applied()).isZero();
        assertThat(tableExists(ds, "pay_flyway_history")).isTrue();
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
