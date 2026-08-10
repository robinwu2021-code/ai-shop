package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 测试库的表要跟得上 Flyway 迁移 —— **两份 schema 的对账**。
 *
 * <p>这个仓库有两套建表脚本：真实库走 `db/migration/V*.sql`（Flyway），
 * 测试走手写的 `schema-test.sql`。**它们是两份真源**，而没有任何东西比对过。
 *
 * <p>代价实测过：加了 `prd_store_stock` 迁移之后忘了同步测试 schema，
 * 症状不是「表不存在」这种一眼可辨的错，而是**下单返回「库存不足」**——
 * 因为查库存的那次查询抛了异常，被上层当成锁定失败吞掉了。
 * 排查方向一开始完全跑偏到库存逻辑上。
 *
 * <p>只比表名不比列：列的差异靠具体用例暴露，而「整张表没建」会让
 * 一大片用例以奇怪的方式失败，成本完全不同。
 */
class SchemaParityTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("★ 迁移里建的每张表，H2 测试 schema 里都要有")
    void everyMigratedTableExistsInTestSchema() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        // 从 shop-app 模块目录或仓库根目录跑都能找到
        Path migrations = resolve(root, "src/main/resources/db/migration",
                "backend/shop-app/src/main/resources/db/migration");
        Path testSchema = resolve(root, "src/test/resources/schema-test.sql",
                "backend/shop-app/src/test/resources/schema-test.sql");

        Set<String> migrated = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                collect(Files.readString(f, StandardCharsets.UTF_8), migrated);
            }
        }
        Set<String> inTest = new LinkedHashSet<>();
        collect(Files.readString(testSchema, StandardCharsets.UTF_8), inTest);

        List<String> missing = migrated.stream().filter(t -> !inTest.contains(t)).toList();
        assertThat(missing)
                .as("这些表在 Flyway 迁移里建了，但 H2 测试 schema 里没有：%s\n"
                        + "加迁移时要同步 backend/shop-app/src/test/resources/schema-test.sql。\n"
                        + "不同步的症状**不是「表不存在」**，而是一大片用例以奇怪的方式失败 —— "
                        + "上一次是下单返回「库存不足」，因为查库存的查询抛了异常被当成锁定失败吞掉。", missing)
                .isEmpty();
    }

    private static void collect(String sql, Set<String> out) {
        Matcher m = CREATE_TABLE.matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase());
        }
    }

    private static Path resolve(Path root, String... candidates) {
        for (String c : candidates) {
            Path p = root.resolve(c);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("找不到 schema 文件，试过：" + String.join(", ", candidates));
    }
}
