package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 平台基础库换表的工作清单：平台句柄下的实体，和新库 {@code n_platform} 的表对不上的地方。
 *
 * <p>新库的结构取自设计的建表语句（测试资源 {@code db/n_platform/V1}，补充在 V2）。对每个绑在平台句柄上的
 * 实体（{@code PlatformTableMapper<实体>}），按设计的表名对照找到它在新库里的表，列出：表在新库里不存在、
 * 实体有而新表没有的列。结果和 {@code backend/known-platform-schema-gaps.txt} 比：
 * <ul>
 *   <li>出现清单里没有的差异 —— 红：新写的代码又和新库拉开了距离；</li>
 *   <li>清单里有、实际已经没有的 —— 也红：做完一项要从清单里划掉，清单只准变短。</li>
 * </ul>
 * 清单清空，平台基础库的实体就可以整体绑到新库。读源码，不起上下文。
 */
class PlatformSchemaGapTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path KNOWN = BACKEND.resolve("known-platform-schema-gaps.txt");

    /** 旧表 → 新库里的表（设计的表名对照里改名或并入的那几张；其余同名）。 */
    private static final Map<String, String> RENAMED = Map.of(
            "mch_channel_area", "mch_store_service_area",
            "mch_channel_pickup", "mch_store_service_area",
            "mch_entity_community", "mch_store_service_area",
            "mch_fulfillment_channel", "mch_store_fulfillment",
            "mch_personnel_account", "mch_personnel",
            "mch_store_role", "mch_grant",
            "usr_mp_session", "usr_session",
            "sys_ops_staff", "ops_staff");

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE `(\\w+)` \\((.*?)\\n\\) COMMENT", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("(?m)^\\s*`(\\w+)` ");
    private static final Pattern MARKED = Pattern.compile("PlatformTableMapper<(?:[\\w.]+\\.)?(\\w+)>");
    private static final Pattern TABLE_NAME = Pattern.compile("@TableName\\((?:value\\s*=\\s*)?\"(\\w+)\"");
    private static final Pattern FIELD = Pattern.compile(
            "(?m)^\\s*(?:@TableField\\(([^)]*)\\)\\s*)?private\\s+(?!static)[\\w<>,.? ]+\\s+(\\w+)\\s*(?:=[^;]*)?;");

    @Test
    @DisplayName("平台句柄下的实体与新库的差异只准变少")
    void gaps_only_shrink() throws IOException {
        Map<String, Set<String>> schema = schema();
        Set<String> actual = new TreeSet<>();
        int entities = 0;
        for (Path entity : platformEntities()) {
            entities++;
            String source = Files.readString(entity, StandardCharsets.UTF_8);
            var tableName = TABLE_NAME.matcher(source);
            if (!tableName.find()) {
                continue;
            }
            String table = tableName.group(1);
            String target = RENAMED.getOrDefault(table, table);
            String name = entity.getFileName().toString().replace(".java", "");
            Set<String> columns = schema.get(target);
            if (columns == null) {
                actual.add(name + " " + table + " -> " + target + " : 新库没有这张表");
                continue;
            }
            if (!target.equals(table)) {
                actual.add(name + " " + table + " -> " + target + " : 表要改名或并入");
            }
            var field = FIELD.matcher(source);
            while (field.find()) {
                String options = field.group(1);
                if (options != null && options.contains("exist = false")) {
                    continue;
                }
                String column = options != null && options.matches(".*value\\s*=\\s*\"(\\w+)\".*")
                        ? options.replaceAll(".*value\\s*=\\s*\"(\\w+)\".*", "$1")
                        : field.group(2).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
                if (!columns.contains(column)) {
                    actual.add(name + " " + table + " -> " + target + " : 新表缺列 " + column);
                }
            }
        }
        assertThat(entities).as("一个平台句柄下的实体都没找到 —— 标记接口改名了？").isGreaterThan(15);

        Set<String> known = new TreeSet<>();
        for (String line : Files.readAllLines(KNOWN, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                known.add(line.trim());
            }
        }
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(known);
        Set<String> gone = new TreeSet<>(known);
        gone.removeAll(actual);
        assertThat(added).as("新出现的差异（代码又和新库拉开了距离）：\n  %s", String.join("\n  ", added)).isEmpty();
        assertThat(gone).as("这些已经对齐了，从 known-platform-schema-gaps.txt 里删掉：\n  %s", String.join("\n  ", gone))
                .isEmpty();
    }

    private static List<Path> platformEntities() throws IOException {
        Map<String, Path> entities = new LinkedHashMap<>();
        Set<String> marked = new LinkedHashSet<>();
        for (String module : List.of("shop-merchant", "shop-core")) {
            try (var files = Files.walk(BACKEND.resolve(module).resolve("src/main/java"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    var m = MARKED.matcher(source);
                    while (m.find()) {
                        marked.add(m.group(1));
                    }
                    if (source.contains("@TableName")) {
                        entities.put(file.getFileName().toString().replace(".java", ""), file);
                    }
                }
            }
        }
        List<Path> result = new ArrayList<>();
        for (String name : marked) {
            if (entities.containsKey(name)) {
                result.add(entities.get(name));
            }
        }
        return result;
    }

    private static Map<String, Set<String>> schema() throws IOException {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        // 设计（V1）加上补充里新建的表（V2，如服务范围网格）
        String sql = Files.readString(BACKEND.resolve("shop-app/src/test/resources/db/n_platform/V1__platform_design.sql"),
                StandardCharsets.UTF_8) + "\n" + Files.readString(
                BACKEND.resolve("shop-app/src/test/resources/db/n_platform/V2__platform_supplement.sql"), StandardCharsets.UTF_8);
        var table = TABLE.matcher(sql);
        while (table.find()) {
            Set<String> columns = new LinkedHashSet<>();
            var column = COLUMN.matcher(table.group(2));
            while (column.find()) {
                columns.add(column.group(1));
            }
            tables.put(table.group(1), columns);
        }
        return tables;
    }
}
