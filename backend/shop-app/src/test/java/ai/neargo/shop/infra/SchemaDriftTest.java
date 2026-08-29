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
import java.util.List;
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
 *
 * <h2>⚠️ 它比的是<b>列</b>，不比<b>值</b> —— 而值也会两边不一样</h2>
 *
 * <p>同一条 {@code INSERT} 灌进去的字面量，两个方言可能存成<b>不同的字节</b>：
 *
 * <pre>
 * 迁移里写的     '[\"CN\"]'
 * MariaDB 解转义  → 库里是  ["CN"]
 * H2 不解转义     → 库里是  [\"CN\"]
 * </pre>
 *
 * <p>后果是<b>方向相反</b>的判断：{@code markets.contains("\"CN\"")}
 * <b>在生产成立、在测试永远不成立</b>。2026-08-29 真炸过一次 ——
 * 症状是一个与支付通道毫无关系的用例报「没有可用支付通道」
 * （{@code StoreSettleFlowTest} 两条红，真因在 {@code MasterDataServiceImpl.marketAllowed}）。
 *
 * <p><b>所以：拿 JSON / 文本列做「字符串包含」判断的代码，测试结论与生产可能相反。</b>
 * 要比就按 token 比（去掉 {@code []"\ } 与空白后逐个 equals），别用 {@code contains}。
 *
 * <p>这条目前<b>没有闸门</b>。它与「MariaDB → MySQL 将来换库」是两条不同的轴：
 * 那条有 {@code scripts/check-sql-portability.mjs}，而<b>这条（H2 测试 ↔ MariaDB 生产）
 * 决定的是「今天的测试结论算不算数」</b>，现在只有这段注释。
 * 立闸的方案见 {@code docs/technical/design/守卫与闸门-问题与优化方案.md} §2.1③ 与方案第 9 条。
 */
class SchemaDriftTest {

    /*
     * `IF NOT EXISTS` 必须可选，缩进必须宽松 —— 否则**整张表被静默漏掉**，
     * 症状却伪装成下游某支迁移「ALTER 了一张不存在的表」（V187/V195/V213 就是这么炸的：
     * 它们写的是裸 `CREATE TABLE` + 2 空格缩进，而 120 份用 4 空格 + IF NOT EXISTS）。
     * gen-test-schema.py 一直是 `CREATE TABLE(?: IF NOT EXISTS)?` 且不挑缩进 ——
     * **两个重放器必须同口径**，这里严于生成器就等于凭空造出一份差异。
     */
    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?(\\w+)\\s*\\((.*?)\\n\\)", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("^\\s{2,}(\\w+)\\s+[A-Z]", Pattern.MULTILINE);
    private static final Set<String> NOT_COLUMNS = Set.of("UNIQUE", "KEY", "CONSTRAINT", "PRIMARY", "INDEX");
    private static final Pattern RENAME = Pattern.compile(
            "ALTER TABLE\\s+(\\w+)\\s+RENAME COLUMN\\s+(\\w+)\\s+TO\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    /**
     * 一条 ALTER 可以带**多个** ADD COLUMN，所以先抓整条语句，再逐个抽列。
     *
     * <p>此前这里只抓「ALTER TABLE x ADD COLUMN y」，一条语句里的第二列起全被漏掉 ——
     * 而漏掉的表现是「迁移与 schema-test.sql 不一致」，让人去查 schema-test.sql，
     * 真因却在解析器里。**一个会静默少读的校验器，比没有校验器更危险。**
     */
    private static final Pattern ALTER_STMT = Pattern.compile(
            "ALTER TABLE\\s+(\\w+)\\s+((?:.|\\n)*?);", Pattern.CASE_INSENSITIVE);
    /*
     * `IF NOT EXISTS` 要跳过，否则 `(\w+)` 把 **IF 抓成列名** ——
     * 解析出一列叫 `IF`，真正那一列不见了，于是报「列不一致」，
     * 而差异里两边看着都对（踩过）。这与上面那条注释是同一个教训：
     * 一个会静默读错的校验器，比没有校验器更危险。
     *
     * 幂等迁移不是可选风格（见 V18 的说明：一次失败的迁移不该需要人手工进库
     * 才能重试），所以解析器要认它。
     */
    private static final Pattern ADD_COLUMN_NAME = Pattern.compile(
            "ADD COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");
    /** 表改名。不认它的话，后续针对新表名的 ALTER 全部报「该表尚未建立」，而真因在几十行之前。 */
    private static final Pattern RENAME_TABLE = Pattern.compile(
            "ALTER TABLE\\s+(\\w+)\\s+RENAME TO\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "DROP TABLE\\s+(?:IF EXISTS\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "ALTER TABLE\\s+(\\w+)\\s+DROP COLUMN\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * <b>版本号不能撞。</b>Flyway 遇到两个同号迁移是<b>启动即失败</b>（"Found more than one
     * migration with version N"），而整套测试都跑在生成的 {@code schema-test.sql} 上、
     * <b>根本不跑 Flyway</b> —— 于是撞号在 {@code mvn test} 里完全看不见，
     * 一直到真起服务连真库那一刻才炸。
     *
     * <p>本仓库并行开发时这已经发生过三次：两个人各自 {@code ls} 了一遍迁移目录、
     * 各自取了下一个号。靠"提交前记得看一眼"防不住，只能让它在测试里当场失败。
     */
    @Test
    @DisplayName("★ 迁移版本号不能重复（Flyway 撞号是启动即失败，而测试不跑 Flyway）")
    void migrationVersionsAreUnique() throws IOException {
        Path dir = Path.of("").toAbsolutePath().resolve("src/main/resources/db/migration");
        Map<Integer, List<String>> byVersion = new LinkedHashMap<>();
        try (var files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .forEach(f -> byVersion
                            .computeIfAbsent(versionOf(f), k -> new java.util.ArrayList<>())
                            .add(f.getFileName().toString()));
        }
        List<String> clashes = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " → " + String.join(" / ", e.getValue()))
                .toList();
        assertThat(clashes)
                .withFailMessage("迁移版本号重复，Flyway 会启动即失败：%n  %s%n"
                        + "把后加的那份改成当前最大号 +1（改名即可，内容不用动）",
                        String.join("\n  ", clashes))
                .isEmpty();
    }

    @Test
    @DisplayName("Flyway 脚本与 H2 测试 schema 的列必须一致")
    void migrationAndTestSchemaMatch() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Map<String, Set<String>> migration = new LinkedHashMap<>();
        // 自动发现全部迁移，而不是逐条手写 —— 手写的话每加一版都要记得改测试，迟早会忘
        try (var files = Files.list(root.resolve("src/main/resources/db/migration"))) {
            // **按版本号数字排，不是字典序** —— 字典序会把 V15 排在 V2 前面，
            // 于是 V15 的 ALTER 在 mch_entity 建表之前重放，下面 tables.get() 拿到 null
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
        // 表改名要在其余 ALTER 之前重放 —— 同一份迁移里往往是「先改名，再往新名上加列」
        for (Matcher m = RENAME_TABLE.matcher(sql); m.find(); ) {
            Set<String> cols = tables.remove(m.group(1));
            if (cols != null) {
                tables.put(m.group(2), cols);
            }
        }
        for (Matcher m = RENAME.matcher(sql); m.find(); ) {
            Set<String> cols = tables.get(m.group(1));
            if (cols != null && cols.remove(m.group(2))) {
                cols.add(m.group(3));
            }
        }
        // DROP TABLE 也要重放 —— 漏掉它，被删的表会一直留在「迁移视图」里，
        // 于是测试反过来指责 schema-test.sql「少了一张表」，而那张表本就该没有
        for (Matcher m = DROP_TABLE.matcher(sql); m.find(); ) {
            tables.remove(m.group(1));
        }
        // DROP COLUMN 同理：gen-test-schema.py 早就支持了，这边一直没有 ——
        // 两个重放器必须同口径，否则每次有人删列就会莫名其妙红一次
        for (Matcher m = DROP_COLUMN.matcher(sql); m.find(); ) {
            Set<String> cols = tables.get(m.group(1));
            if (cols != null) {
                cols.remove(m.group(2));
            }
        }
        for (Matcher stmt = ALTER_STMT.matcher(sql); stmt.find(); ) {
            String table = stmt.group(1);
            for (Matcher m = ADD_COLUMN_NAME.matcher(stmt.group(2)); m.find(); ) {
                Set<String> cols = tables.get(table);
                // 静默跳过是上一版的病根：顺序错了也照样跑完，只是少几列。**宁可炸**。
                if (cols == null) {
                    throw new IllegalStateException(
                            "ALTER TABLE " + table + " ADD COLUMN " + m.group(1)
                                    + " —— 该表尚未建立。迁移重放顺序错了，或迁移本身有问题");
                }
                cols.add(m.group(1));
            }
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
