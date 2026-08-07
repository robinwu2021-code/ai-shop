package ai.neargo.shop.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拦截「两份 DDL 漂移」：生产走 {@code db/migration/V*.sql}（MySQL），
 * 测试走 {@code schema-test.sql}（H2）。后者是前者的机械转换，但**没有任何机制阻止有人只改一边**
 * —— 只改 MySQL 那份，测试仍然全绿，问题要到部署后才炸；只改 H2 那份更糟，测试绿着而生产根本没这列。
 *
 * <p>本测试比对两边的「表 → 列集合」，不一致就失败并指出差在哪。
 */
class SchemaDriftTest {

    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\((.*?)\\n\\)", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("^\\s{4}(\\w+)\\s+[A-Z]", Pattern.MULTILINE);
    private static final Set<String> NOT_COLUMNS = Set.of("UNIQUE", "KEY", "CONSTRAINT", "PRIMARY", "INDEX");
    private static final Pattern RENAME = Pattern.compile(
            "ALTER TABLE\\s+(\\w+)\\s+RENAME COLUMN\\s+(\\w+)\\s+TO\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ALTER TABLE\\s+(\\w+)\\s+ADD COLUMN\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    @Test
    @DisplayName("Flyway 脚本与 H2 测试 schema 的列必须一致")
    void migrationAndTestSchemaMatch() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Map<String, Set<String>> migration = new LinkedHashMap<>();
        // 自动发现全部迁移，而不是逐条手写 —— 手写的话每加一版都要记得改测试，迟早会忘
        try (var files = Files.list(root.resolve("src/main/resources/db/migration"))) {
            // **按版本号数字排，不是字典序** —— 字典序会把 V15 排在 V2 前面，
            // 于是 V15 的 ALTER 在 usr_merchant 建表之前重放，下面 tables.get() 拿到 null
            // 静默跳过，产出一份缺列的迁移视图，测试反过来去指责 schema-test.sql 多了列。
            // gen-test-schema.py 早就修了这条，这里漏了 —— 两个重放器必须同口径。
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(SchemaDriftTest::versionOf)).toList()) {
                replay(Files.readString(f, StandardCharsets.UTF_8), migration);
            }
        }
        Map<String, Set<String>> test = parse(root.resolve("src/test/resources/schema-test.sql"));

        assertThat(test.keySet())
                .as("表集合不一致 —— 新建表时两份 DDL 都要加")
                .containsExactlyInAnyOrderElementsOf(migration.keySet());

        migration.forEach((table, columns) -> assertThat(test.get(table))
                .as("表 %s 的列不一致（改了 Flyway 就要同步改 schema-test.sql）", table)
                .containsExactlyInAnyOrderElementsOf(columns));
    }

    /** 从 {@code V15__xxx.sql} 取出 15。文件名不合规直接失败，好过按 0 排到最前面。 */
    private static int versionOf(Path f) {
        Matcher m = VERSION.matcher(f.getFileName().toString());
        if (!m.find()) {
            throw new IllegalStateException("迁移文件名不是 V<数字>__ 开头：" + f.getFileName());
        }
        return Integer.parseInt(m.group(1));
    }

    /** 重放一份迁移脚本：CREATE 收表，ALTER RENAME/ADD 改表 —— 与 gen-test-schema.py 同口径。 */
    private void replay(String sql, Map<String, Set<String>> tables) {
        collectCreates(sql, tables);
        for (Matcher m = RENAME.matcher(sql); m.find(); ) {
            Set<String> cols = tables.get(m.group(1));
            if (cols != null && cols.remove(m.group(2))) {
                cols.add(m.group(3));
            }
        }
        for (Matcher m = ADD_COLUMN.matcher(sql); m.find(); ) {
            Set<String> cols = tables.get(m.group(1));
            // 静默跳过是上一版的病根：顺序错了也照样跑完，只是少几列。**宁可炸**。
            if (cols == null) {
                throw new IllegalStateException(
                        "ALTER TABLE " + m.group(1) + " ADD COLUMN " + m.group(2)
                                + " —— 该表尚未建立。迁移重放顺序错了，或迁移本身有问题");
            }
            cols.add(m.group(2));
        }
    }

    private Map<String, Set<String>> parse(Path file) throws IOException {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        replay(Files.readString(file, StandardCharsets.UTF_8), tables);
        return tables;
    }

    private void collectCreates(String sql, Map<String, Set<String>> tables) {
        Matcher t = TABLE.matcher(sql);
        while (t.find()) {
            Set<String> columns = new LinkedHashSet<>();
            Matcher c = COLUMN.matcher(t.group(2));
            while (c.find()) {
                String name = c.group(1);
                if (!NOT_COLUMNS.contains(name.toUpperCase())) {
                    columns.add(name);
                }
            }
            tables.put(t.group(1), columns);
        }
    }
}
