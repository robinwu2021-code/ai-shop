package ai.neargo.shop.infra;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code schema-test.sql} 必须**真的是生成出来的**。
 *
 * <p><b>为什么 {@link SchemaDriftTest} 不够</b>：那一条比对的是「迁移 ↔ schema-test.sql」，
 * 两边都由它自己那套 Java 重放来解析 —— 它<b>从不运行生成器</b>。
 * 于是发生过这样一件事：{@code gen-test-schema.py} 撞上 V6 的 {@code DELETE} 就退出，
 * 几个月里根本跑不出东西，而这份产物被人手工维护着跟迁移对齐 ——
 * SchemaDriftTest 一路全绿，抬头那句「自动生成，勿手改」一路是假的。
 *
 * <p>没人会定期去跑一个不报错的脚本。**只有让它在 {@code mvn test} 里失败，
 * 它坏掉的那天才会有人知道。**
 *
 * <p>本测试守三件事，一件比一件靠后：
 * <ol>
 *   <li>生成器<b>能跑完</b> —— 单这一条就能把上面那几个月缩短成一次提交</li>
 *   <li>产出与仓库里的文件<b>结构等价</b>（表集合、逐表列集合）——
 *       比字节相同宽松是有意的：列顺序、空行、注释都不该让构建变红</li>
 *   <li>产出<b>真能在 H2 上建起来</b> —— 这一条防的是最贵的一类失败：
 *       schema 语法错时症状是「Spring 上下文起不来」，而报错指向一个
 *       毫不相干的 Controller，根因在生成器上，没人会往那儿看</li>
 * </ol>
 */
class SchemaGeneratorTest {

    /** 只解析最终形态的 {@code CREATE TABLE}：两份文件都已是建表语句，不需要重放 ALTER。 */
    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\((.*?)\\n\\)", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("^\\s{4}(\\w+)\\s+[A-Z]", Pattern.MULTILINE);
    private static final Set<String> NOT_COLUMNS = Set.of("UNIQUE", "KEY", "CONSTRAINT", "PRIMARY", "INDEX");

    @Test
    @DisplayName("★ schema-test.sql 必须真的是生成出来的（生成器能跑 + 产出等价 + H2 能建）")
    void generatorReproducesCommittedSchema() throws Exception {
        Path module = Path.of("").toAbsolutePath();          // backend/shop-app
        Path script = module.getParent().resolve("scripts/gen-test-schema.py");
        Path committed = module.resolve("src/test/resources/schema-test.sql");
        assertThat(script).as("生成器脚本不在了？抬头那句「自动生成」就该一起去掉").exists();

        Path generated = Files.createTempFile("schema-gen", ".sql");
        int exit = runGenerator(script, generated);

        // 生成器跑不动时**必须红**。此前它坏了几个月都没人发现，
        // 正是因为没有任何一处会去执行它
        assertThat(exit)
                .withFailMessage("gen-test-schema.py 跑失败（退出码 %d）。"
                        + "schema-test.sql 声称自己是它生成的 —— 生成器坏了，这句话就是假的。%n"
                        + "手工跑一次看报错：python3 backend/scripts/gen-test-schema.py /tmp/x.sql", exit)
                .isZero();

        Map<String, Set<String>> gen = parse(generated);
        Map<String, Set<String>> repo = parse(committed);

        assertThat(gen.keySet())
                .as("生成器产出的表集合与 schema-test.sql 不一致 —— "
                        + "要么有人手改了这份产物，要么生成器漏读了某条迁移")
                .containsExactlyInAnyOrderElementsOf(repo.keySet());

        gen.forEach((table, columns) -> assertThat(repo.get(table))
                .as("表 %s：生成器产出的列与 schema-test.sql 不一致 —— "
                        + "重新生成一次（python3 backend/scripts/gen-test-schema.py），不要手改产物", table)
                .containsExactlyInAnyOrderElementsOf(columns));

        assertH2CanLoad(generated);
        Files.deleteIfExists(generated);
    }

    private int runGenerator(Path script, Path out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("python3", script.toString(), out.toString());
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            /*
             * 机器上没有 python3 就跳过，而不是判失败 —— 这条守卫的价值在于「生成器坏了要红」，
             * 而不是「谁的机器上没装 python 也要红」。本仓库另有 api-align.py 同样依赖 python3，
             * 所以正常开发机上它一定会真跑。
             */
            Assumptions.abort("没有 python3，跳过生成器守卫：" + e.getMessage());
            return -1;
        }
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("生成器 60 秒没跑完：" + log);
        }
        int exit = p.exitValue();
        if (exit != 0) {
            System.out.println("[gen-test-schema.py]\n" + log);
        }
        return exit;
    }

    /**
     * 拿 H2 真跑一遍。
     *
     * <p>结构等价只说明「表和列都在」，说明不了「这份 SQL 合法」——
     * {@code AFTER city_code} 泄漏进建表语句时，两边的列集合完全一致，而 H2 根本建不起来。
     */
    private void assertH2CanLoad(Path sql) {
        String url = "jdbc:h2:mem:schemagen_" + System.nanoTime() + ";MODE=MySQL";
        try (var conn = java.sql.DriverManager.getConnection(url, "sa", "")) {
            org.h2.tools.RunScript.execute(conn, Files.newBufferedReader(sql, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(
                    "生成的 schema 在 H2 上建不起来 —— 真跑的时候症状会是「Spring 上下文起不来」，"
                            + "而报错指向一个毫不相干的 Controller。根因在 gen-test-schema.py：\n"
                            + e.getMessage(), e);
        }
    }

    private Map<String, Set<String>> parse(Path file) throws IOException {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher t = TABLE.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (t.find()) {
            Set<String> columns = new LinkedHashSet<>();
            Matcher c = COLUMN.matcher(t.group(2));
            while (c.find()) {
                if (!NOT_COLUMNS.contains(c.group(1).toUpperCase())) {
                    columns.add(c.group(1));
                }
            }
            tables.put(t.group(1), columns);
        }
        return tables;
    }
}
