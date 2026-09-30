package ai.neargo.shop.report.dao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static String read() {
        // 从 target/classes 之外读源文件：测试跑在模块根目录下
        Path p = Path.of("src/main/resources/db/report/V1__report_baseline.sql");
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 V1 迁移（" + p.toAbsolutePath()
                    + "）—— 测试库的建表语句以它为准，读不到就不能假装建好了", e);
        }
    }
}
