package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 商品领域不访问旧库（TDD-商品领域迁到门店交易库 §2.2 L3）。五条：
 *
 * <ol>
 *   <li>R1 商品包的 Mapper 只能继承 {@code ProductTableMapper}（评价、专题、收藏继承 {@code MemberMarketingTableMapper}）；</li>
 *   <li>R2 它们用到的表（实体 {@code @TableName} 与注解 SQL）必须在目标库的设计 DDL 里；</li>
 *   <li>R3 商品包里不出现带库名的表引用，也不出现 {@code ai_shop}；</li>
 *   <li>R4 商品包里不注入没点名句柄的数据源、JdbcTemplate、JdbcClient；</li>
 *   <li>R5 商品包以外的生产代码不 import 商品的 Mapper 与实体，只走 {@code spi.product} 端口。</li>
 * </ol>
 *
 * <p>改造是分批做的，现状的违规记在 {@code product-db-isolation-baseline.txt}：多出来的违规失败，
 * 修好了却还留在基线里的也失败（只许减少）。重写基线：{@code -Dproduct.isolation.record=true}。
 */
@DisplayName("架构 · 商品领域不访问旧库")
class ProductDatabaseIsolationTest {
    private static final Path ROOT = Paths.get(System.getProperty("user.dir")).getParent().getParent();
    private static final Path PRODUCT = ROOT.resolve("backend/shop-core/src/main/java/ai/neargo/shop/product");
    private static final Path BASELINE = ROOT.resolve(
            "backend/shop-app/src/test/resources/product-db-isolation-baseline.txt");
    private static final Map<String, String> TARGET = Map.of(
            "ProductTableMapper", "docs/technical/design/schema/ddl/05-n_trade.sql",
            "MemberMarketingTableMapper", "docs/technical/design/schema/ddl/07-n_member.sql");
    private static final Set<String> SYSTEM_TABLES = Set.of("information_schema");
    private static final Pattern IFACE = Pattern.compile(
            "\\binterface\\s+(\\w+)\\s*(?:<[^{]*?>)?\\s*extends\\s+([^{]+)\\{");
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|into|update)\\s+`?([a-z][a-z0-9_]*)`?");
    private static final Pattern TABLE_NAME = Pattern.compile(
            "@TableName\\(\\s*(?:value\\s*=\\s*)?\"(\\w+)\"[^)]*\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*"
                    + "public\\s+(?:final\\s+)?(?:class|record)\\s+(\\w+)");
    private static final Pattern JDBC = Pattern.compile(
            "\\b(DataSource|JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)\\s+\\w+\\s*[,)=;]");

    @Test
    void productStaysOffTheLegacyDatabases() throws IOException {
        Set<String> violations = new TreeSet<>();
        Map<String, String> entityTable = entityTables();
        Map<String, Set<String>> ddl = new HashMap<>();
        for (var e : TARGET.entrySet()) {
            ddl.put(e.getKey(), ddlTables(ROOT.resolve(e.getValue())));
        }
        int mappers = 0;
        for (Path file : javaFiles(PRODUCT)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            Matcher m = IFACE.matcher(text);
            while (m.find()) {
                String ext = m.group(2);
                String marker = markerOf(ext);
                if (marker == null) {
                    continue;
                }
                mappers++;
                String mapper = m.group(1);
                if (!TARGET.containsKey(marker)) {
                    violations.add("R1 " + mapper + " 继承 " + marker);
                }
                Set<String> tables = new TreeSet<>();
                Matcher generic = Pattern.compile("\\w+\\s*<\\s*([\\w.]+)").matcher(ext);
                if (generic.find()) {
                    String entity = generic.group(1).substring(generic.group(1).lastIndexOf('.') + 1);
                    if (entityTable.containsKey(entity)) {
                        tables.add(entityTable.get(entity));
                    }
                }
                Matcher sql = SQL_TABLE.matcher(body(text, m.start()));
                while (sql.find()) {
                    String t = sql.group(1).toLowerCase();
                    if (t.contains("_") && !SYSTEM_TABLES.contains(t)) {
                        tables.add(t);
                    }
                }
                Set<String> known = ddl.getOrDefault(marker, Set.of());
                for (String t : tables) {
                    if (!known.contains(t)) {
                        violations.add("R2 " + mapper + " 用到 " + t + "，不在 "
                                + (TARGET.containsKey(marker) ? Paths.get(TARGET.get(marker)).getFileName() : "目标库 DDL"));
                    }
                }
            }
            for (String line : text.split("\n")) {
                if (line.contains("\"") && (Pattern.compile("\\b\\w+\\.(prd|rvw|mbr)_\\w+").matcher(line).find()
                        || line.contains("ai_shop"))) {
                    violations.add("R3 " + name + " 带库名的表引用或旧库名：" + line.strip());
                }
            }
            if (JDBC.matcher(text).find() && !text.contains("ModuleDataSources.PRODUCT_")
                    && !text.contains("ModuleDataSources.MEMBER_MARKETING_")) {
                violations.add("R4 " + name + " 注入了没点名句柄的数据源或 JDBC 模板");
            }
        }
        Pattern productImport = Pattern.compile(
                "^import ai\\.neargo\\.shop\\.product\\.(?:review\\.)?(?:mapper|entity)\\.([\\w.]+);", Pattern.MULTILINE);
        for (String module : List.of("backend", "backend-plugins")) {
            for (Path file : javaFiles(ROOT.resolve(module))) {
                String path = file.toString().replace('\\', '/');
                if (!path.contains("/src/main/java/") || path.contains("/ai/neargo/shop/product/")) {
                    continue;
                }
                Matcher m = productImport.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    violations.add("R5 " + file.getFileName() + " import " + m.group(1));
                }
            }
        }
        assertThat(mappers).as("一个 Mapper 都没扫到 —— 路径错了").isGreaterThan(20);

        if (Boolean.getBoolean("product.isolation.record")) {
            Files.writeString(BASELINE, "# 商品领域不访问旧库：现状违规（只许减少）。由 ProductDatabaseIsolationTest 用 "
                    + "-Dproduct.isolation.record=true 生成\n" + String.join("\n", violations) + "\n", StandardCharsets.UTF_8);
            return;
        }
        Set<String> baseline = new TreeSet<>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                baseline.add(line);
            }
        }
        Set<String> added = new TreeSet<>(violations);
        added.removeAll(baseline);
        Set<String> fixed = new TreeSet<>(baseline);
        fixed.removeAll(violations);
        assertThat(added).as("新增的违规（见类注释的五条规则）").isEmpty();
        assertThat(fixed).as("这些已经修好了，请从 product-db-isolation-baseline.txt 里删掉").isEmpty();
    }

    private static String markerOf(String ext) {
        for (String marker : List.of("ProductTableMapper", "MemberMarketingTableMapper", "TradeTableMapper",
                "PlatformTableMapper", "IndustryTableMapper", "CommonTableMapper", "PayMerchantTableMapper",
                "TradeSqlMapper", "PlatformSqlMapper", "BaseMapper")) {
            if (Pattern.compile("\\b" + marker + "\\b").matcher(ext).find()) {
                return marker;
            }
        }
        return null;
    }

    private static Map<String, String> entityTables() throws IOException {
        Map<String, String> out = new HashMap<>();
        for (Path file : javaFiles(ROOT.resolve("backend/shop-core/src/main/java"))) {
            Matcher m = TABLE_NAME.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                out.putIfAbsent(m.group(2), m.group(1).toLowerCase());
            }
        }
        return out;
    }

    private static Set<String> ddlTables(Path ddl) throws IOException {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("(?i)CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?`?(\\w+)`?")
                .matcher(Files.readString(ddl, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1).toLowerCase());
        }
        assertThat(out).as("设计 DDL 里一张表都没解析到：%s", ddl).isNotEmpty();
        return out;
    }

    /** 从接口声明处的 '{' 起按括号配平取接口体；SQL 里的 #{...} 本身配平，不影响。 */
    private static String body(String text, int start) {
        int open = text.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(open, i + 1);
            }
        }
        return text.substring(open);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
