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

    /** 建表 / 改名 / 删表，**一个正则**，这样三者能按出现顺序处理（见 {@link #collect}） */
    private static final Pattern CREATE_OR_RENAME_OR_DROP = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (\\w+)"
                    + "|ALTER TABLE\\s+(\\w+)\\s+RENAME TO\\s+(\\w+)"
                    + "|DROP TABLE(?:\\s+IF EXISTS)?\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE);

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
            /*
             * **按版本号数字排序重放**，不是按文件名字典序，也不是 Files.list 的任意顺序：
             * 有了 RENAME 之后「谁先谁后」就有意义了 —— V162 把 msg_* 改成 notify_*，
             * 先看到 V162 再看到建表的话，改名落空，产出的期望表名是旧的那一批。
             * 字典序同样不行：V15 会排在 V2 前面（生成器里踩过同一个坑）。
             */
            for (Path f : files.filter(p -> p.toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparingInt(SchemaParityTest::versionOf)).toList()) {
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

    /**
     * 收集这段 SQL 里表名的**净变化**：建表加进来，{@code RENAME TO} 就地改名，
     * {@code DROP TABLE} 拿掉。
     *
     * <p>不认改名的话，这条守卫会拿**改名前**的表名去测试 schema 里找，
     * 报「msg_message 没建」—— 而它其实建了，只是叫 notify_message 了。
     * 报错文案指向「加迁移忘了同步测试 schema」，把人引向一个不存在的问题。
     *
     * <p><b>不认删表也一样会误报</b>：一张后来被 DROP 掉的表，生成器重放时不会出现在
     * schema-test.sql 里（那是对的），而这条守卫却仍拿它去找，报「忘了同步」。
     * 上一次是 sys_job_def / sys_job_log —— 定时任务的表迁进独立库之后被 V262 删掉，
     * 生成器与守卫给出了相反的结论，而错的是守卫。
     *
     * <p>三者必须**按出现顺序**处理（用一个合并的正则一次扫过去），
     * 而不是先收全部建表再收全部改名/删表：同一个文件里先删后建同名表是合法的，
     * 分开收就会把「建」当成先发生的那一个。
     */
    private static void collect(String sql, Set<String> out) {
        Matcher m = CREATE_OR_RENAME_OR_DROP.matcher(sql);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(m.group(1).toLowerCase());
            } else if (m.group(2) != null) {
                out.remove(m.group(2).toLowerCase());
                out.add(m.group(3).toLowerCase());
            } else {
                out.remove(m.group(4).toLowerCase());
            }
        }
    }

    /** 文件名里的版本号。V15 必须排在 V2 之后，所以按数字比而不是按字符串比 */
    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
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
