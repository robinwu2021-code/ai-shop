package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话与登录日志这六张表必须**自足** —— 不与任何别的表 join，也没有外键。
 *
 * <h2>为什么要有这条守卫</h2>
 * 「将来能把某一端搬去独立库」不是一次性检查，是**要一直守住的性质**。
 * 加一个 join 的那天不会有任何东西变红，代价要到真去拆库的那天才付 ——
 * 而那时候写 join 的人多半已经不在这个项目上了。
 *
 * <p>外键更直接：它是**数据库层面**的跨表约束，拆库那天它是第一个要拆的东西，
 * 而且拆的时候没法确定还有谁依赖它。
 */
@DisplayName("会话表自足")
class SessionTablesAreSelfContainedTest {

    private static final List<String> TABLES = List.of(
            "usr_session", "usr_login_log",
            "mch_session", "mch_login_log",
            "ops_session", "ops_login_log");

    /** 建这六张表的迁移。 */
    private static final Pattern SESSION_MIGRATION =
            Pattern.compile("^V26[456]__.*\\.sql$");

    private static Path backend() {
        Path here = Paths.get("").toAbsolutePath();
        return here.getFileName().toString().equals("backend") ? here : here.getParent();
    }

    @Test
    @DisplayName("★ 六张表都没有外键 —— 拆库那天它是第一个要拆的东西")
    void noForeignKeys() throws IOException {
        Path dir = backend().resolve("shop-app/src/main/resources/db/migration");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> SESSION_MIGRATION.matcher(p.getFileName().toString()).matches())
                    .toList()) {
                String sql = Files.readString(f, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
                if (sql.contains("FOREIGN KEY") || sql.contains("REFERENCES ")) {
                    offenders.add(f.getFileName().toString());
                }
            }
        }
        assertThat(offenders)
                .as("会话表上出现了外键。它是数据库层面的跨表约束，"
                    + "拆库那天要先拆掉它，而那时没法确定还有谁依赖它")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 持久层的 SQL 里不出现 JOIN —— 一个 join 就把「能拆库」这条性质破了")
    void noJoinsInSessionSql() throws IOException {
        Path dir = backend().resolve("shop-auth-store/src/main/java/ai/neargo/auth/store");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                // 只看真正的 SQL 片段，不看注释：注释里说明「为什么不 join」是应该的
                for (String line : src.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("*") || t.startsWith("//")) {
                        continue;
                    }
                    String upper = t.toUpperCase(Locale.ROOT);
                    if (upper.contains(" JOIN ") || upper.contains("\"JOIN")) {
                        offenders.add(f.getFileName() + ": " + t);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("会话/日志的 SQL 里出现了 JOIN。加它的那天不会有任何东西变红，"
                    + "代价要到真去拆库的那天才付 —— 而那时写它的人多半已经不在这个项目上了")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 这条守卫本身扫得到东西 —— 「什么都没扫到」是这类守卫最常见的死法")
    void guardActuallyScansSomething() throws IOException {
        Path dir = backend().resolve("shop-auth-store/src/main/java/ai/neargo/auth/store");
        assertThat(Files.exists(dir)).as("持久层目录都不在，上面两条等于空转").isTrue();

        long sqlFiles;
        try (Stream<Path> files = Files.list(dir)) {
            sqlFiles = files.filter(p -> p.toString().endsWith("Dao.java")).count();
        }
        assertThat(sqlFiles).as("一个 DAO 都没扫到").isGreaterThanOrEqualTo(2);

        Path migrations = backend().resolve("shop-app/src/main/resources/db/migration");
        long found;
        try (Stream<Path> files = Files.list(migrations)) {
            found = files.filter(p -> SESSION_MIGRATION.matcher(p.getFileName().toString()).matches())
                    .count();
        }
        assertThat(found).as("三条会话迁移应当都在（改了号就要改这里的正则）").isEqualTo(3);
    }

    @Test
    @DisplayName("六张表都建了 —— 少一张就是某一端没有会话表")
    void allSixTablesExist() throws IOException {
        Path schema = backend().resolve("shop-app/src/test/resources/schema-test.sql");
        String sql = Files.readString(schema, StandardCharsets.UTF_8);
        for (String t : TABLES) {
            assertThat(sql).as("%s 不在生成的测试 schema 里", t)
                    .contains("CREATE TABLE IF NOT EXISTS " + t + "\n");
        }
    }
}
