package ai.neargo.shop.report.dao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 报表库的测试库。
 *
 * <p><b>建表语句从 V1 现读现转，不手抄。</b>手抄一份测试 schema 的下场是它会漂 ——
 * 迁移加了一列而测试库没有，症状是一条 <i>Unknown column</i>，而报错指向的是
 * 用到那一列的某个测试，不是「你少抄了一列」。
 *
 * <p>⚠️ <b>这不验证迁移本身。</b>H2 与生产库有四处已记录的方言差
 * （{@code \n} / {@code --} / CROSS JOIN / CHECK 约束），而且 H2 会合并重名索引。
 * V1 能不能在生产跑，只有在<b>真库副本</b>上跑过才算数（本仓库为此在 V336 上挂过 5 分钟）。
 * 这里只验 DAO 的读写语义。
 */
final class ReportDaoTestSupport {

    private ReportDaoTestSupport() {
    }

    static JdbcClient freshDb(String name) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setPoolName("rpt-pool");
        JdbcClient jdbc = JdbcClient.create(ds);
        for (String stmt : h2Schema().split(";")) {
            if (!stmt.isBlank()) {
                jdbc.sql(stmt).update();
            }
        }
        return jdbc;
    }

    /** 把 V1 的 MySQL 建表语句转成 H2 能吃的。转换只做减法，不改结构。 */
    private static String h2Schema() {
        String sql = read();
        return sql
                // 行注释整行去掉（H2 认 --，但里面的中文括号与引号会把后面的语句带偏）
                .replaceAll("(?m)^--.*$", "")
                // 显示宽度：H2 2.x 不认 BIGINT(20) 这种
                .replaceAll("(?i)\\b(BIGINT|INT|TINYINT)\\(\\d+\\)", "$1")
                // 列注释与表尾的 ENGINE/CHARSET/COMMENT
                .replaceAll("(?i)\\s+COMMENT\\s+'(?:[^']|'')*'", "")
                .replaceAll("(?i)\\)\\s*ENGINE=\\w+[^;]*;", ");");
    }

    /**
     * 读 {@code db/report} 下**全部**迁移，按文件名排序后拼起来。
     *
     * <p>⚠️ 起初这里写死了 {@code V1__report_baseline.sql}。加 V2 那天，
     * 测试库里就没有新表 —— 报错是一条 <i>bad SQL grammar</i>，
     * 而它指向的是用到新表的那个测试，不是「你少读了一个迁移」。
     * 与手抄一份 schema 是同一种漂移，只是换了个形态。
     */
    private static String read() {
        // 从 target/classes 之外读源文件：测试跑在模块根目录下
        Path dir = Path.of("src/main/resources/db/report");
        try (var files = Files.list(dir)) {
            List<Path> migrations = files
                    .filter(f -> f.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .toList();
            if (migrations.isEmpty()) {
                throw new IllegalStateException("db/report 下一个迁移都没有 —— "
                        + "读不到就不能假装建好了");
            }
            StringBuilder sb = new StringBuilder();
            for (Path m : migrations) {
                sb.append(Files.readString(m, StandardCharsets.UTF_8)).append("\n;\n");
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("读不到迁移目录（" + dir.toAbsolutePath()
                    + "）—— 测试库的建表语句以它为准", e);
        }
    }
}
