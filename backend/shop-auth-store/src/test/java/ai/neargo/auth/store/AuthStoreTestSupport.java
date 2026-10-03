package ai.neargo.auth.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 起一个 H2 内存库，只建鉴权用到的六张表。
 *
 * <p><b>刻意不在本模块留一份 schema 副本。</b>副本会腐烂 ——
 * 改了迁移而忘了同步副本，测试照样绿，而绿的是一张过时的表。
 * 这里从**生成物** {@code shop-app/src/test/resources/schema-test.sql}
 * （由 {@code scripts/gen-test-schema.py} 从 {@code db/migration} 重放，全仓唯一一份）
 * 里按表名取出需要的那几段。
 *
 * <p>建表语句不在本模块而在 shop-app，是因为这六张表住在**平台库**里 ——
 * 与 shop-job 那种独立库不同，那边的迁移才归模块自己。
 */
public final class AuthStoreTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 六张表：三端 × （会话 + 登录日志）。 */
    private static final List<String> TABLES = List.of(
            "usr_session", "usr_login_log",
            "mch_session", "mch_login_log",
            "ops_session", "ops_login_log");

    private AuthStoreTestSupport() {
    }

    /** 三端的测试档位。TTL 取小值，让用例跑得快；比例关系与生产一致。 */
    public static SessionProfile profile(String pool, String sessionTable, String logTable,
                                         String prefix) {
        return new SessionProfile(pool, sessionTable, logTable, prefix,
                Duration.ofDays(30),      // sessionTtl
                Duration.ofSeconds(60),   // cacheTtl
                Duration.ofSeconds(30),   // identityTtl
                Duration.ofSeconds(5),    // revokePoll
                Duration.ofHours(1),      // lastSeenThrottle
                false, 90);
    }

    public static SessionProfile consumer() {
        return profile("consumer", "usr_session", "usr_login_log", "ctk_");
    }

    public static SessionProfile merchant() {
        return profile("merchant", "mch_session", "mch_login_log", "btk_");
    }

    public static SessionProfile operator() {
        return profile("operator", "ops_session", "ops_login_log", "otk_");
    }

    public static JdbcClient freshDatabase() {
        DataSource ds = new SimpleDriverDataSource(
                new org.h2.Driver(),
                "jdbc:h2:mem:authstore" + SEQ.incrementAndGet()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        for (String stmt : createStatements()) {
            exec(ds, stmt);
        }
        return JdbcClient.create(ds);
    }

    /**
     * 两个 {@link JdbcClient} 指向**同一个库** —— 用来模拟两个实例。
     *
     * <p>跨实例撤销传播那条断言（本方案的存在理由）必须这样验：
     * 同一个库、两份各自的缓存，A 踢人之后 B 要在上界内拒绝。
     */
    public static JdbcClient sameDatabaseAs(JdbcClient other, String url) {
        return JdbcClient.create(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", ""));
    }

    private static List<String> createStatements() {
        String sql = readGeneratedSchema();
        List<String> out = new ArrayList<>();
        for (String table : TABLES) {
            String marker = "CREATE TABLE IF NOT EXISTS " + table + "\n";
            int start = sql.indexOf(marker);
            if (start < 0) {
                throw new IllegalStateException("""
                        生成的 schema-test.sql 里没有 %s。

                        它是生成物，不是手写的。加/改迁移后要重跑：
                          python3 backend/scripts/gen-test-schema.py
                        """.formatted(table));
            }
            int end = sql.indexOf(");", start);
            out.add(sql.substring(start, end + 1));
        }
        return out;
    }

    private static String readGeneratedSchema() {
        Path here = Path.of("").toAbsolutePath();
        Path backend = here.getFileName().toString().equals("backend") ? here : here.getParent();
        Path script = backend.resolve("shop-app/src/test/resources/schema-test.sql");
        if (!Files.exists(script)) {
            throw new IllegalStateException("找不到生成的测试 schema：" + script);
        }
        try {
            return Files.readString(script, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不了 " + script, e);
        }
    }

    private static void exec(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("建表失败：" + sql.lines().findFirst().orElse(""), e);
        }
    }
}
