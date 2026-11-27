package ai.neargo.shop.food.scenario;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * 在 H2 测试库上按行业库建表脚本（{@code db/n_industry/V1、V2}）建指定的表（TDD-餐饮域迁移新库 P1-6）。
 *
 * <p>只取 {@code CREATE TABLE} 语句并清洗 MySQL 方言：去掉列级字符集与排序规则、表选项；H2 的索引名全库唯一，
 * 给索引名加表名前缀。表结构以 MySQL 上的新库测试（{@link FoodIndustryDatabaseTest}）为准，这里只服务 H2 场景测试。
 */
final class IndustrySchema {
    private static final Pattern CREATE =
            Pattern.compile("CREATE TABLE `([a-z_0-9]+)` \\((.*?)\\n\\)[^;]*;", Pattern.DOTALL);
    private static final Pattern INDEX = Pattern.compile("(KEY|INDEX|CONSTRAINT) `([a-z_0-9]+)`");

    private IndustrySchema() {}

    static void create(DataSource source, String... tables) {
        String scripts = read("db/n_industry/V1__industry_design.sql") + "\n"
                + read("db/n_industry/V2__industry_supplement.sql");
        var db = new JdbcTemplate(source);
        for (String table : tables) {
            db.execute(h2(table, scripts));
            for (String clause : alterations(table, scripts)) db.execute(clause);
        }
    }

    /**
     * {@code V2} 对设计表的补列与补键（{@code ALTER TABLE t ADD COLUMN …, ADD [UNIQUE] KEY …}），逐条改成 H2 能执行的语句。
     * 删索引不跟（H2 上多一个旧唯一键不影响单行业的场景测试）。
     */
    private static java.util.List<String> alterations(String table, String scripts) {
        var result = new java.util.ArrayList<String>();
        Matcher m = Pattern.compile("(?m)^ALTER TABLE " + table + " (.*);$").matcher(scripts);
        while (m.find()) {
            for (String clause : clauses(m.group(1))) {
                clause = clause.trim()
                        .replaceAll("\\s+CHARACTER SET \\w+", "")
                        .replaceAll("\\s+COLLATE \\w+", "")
                        .replaceAll("\\s+COMMENT '[^']*'", "");
                Matcher key = Pattern.compile("ADD (UNIQUE )?KEY (\\w+) \\((.*)\\)").matcher(clause);
                if (clause.startsWith("ADD COLUMN ")) result.add("ALTER TABLE " + table + " " + clause);
                else if (key.matches())
                    result.add(key.group(1) == null
                            ? "CREATE INDEX " + table + "__" + key.group(2) + " ON " + table + " (" + key.group(3) + ")"
                            : "ALTER TABLE " + table + " ADD CONSTRAINT " + table + "__" + key.group(2)
                                    + " UNIQUE (" + key.group(3) + ")");
            }
        }
        return result;
    }

    /** 按顶层逗号切子句（括号与引号里的逗号不切）。 */
    private static java.util.List<String> clauses(String body) {
        var parts = new java.util.ArrayList<String>();
        int depth = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if (!quoted && c == '(') depth++;
            else if (!quoted && c == ')') depth--;
            else if (!quoted && depth == 0 && c == ',') {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    private static String h2(String table, String scripts) {
        Matcher m = CREATE.matcher(scripts);
        while (m.find()) {
            if (!m.group(1).equals(table)) continue;
            String body = m.group(2)
                    .replaceAll("\\s+CHARACTER SET \\w+", "")
                    .replaceAll("\\s+COLLATE \\w+", "")
                    // H2 的 JSON 列会把写入的字符串再包成 JSON 字符串值，读回来多一层引号；按文本列建。
                    .replaceAll("(?i)` json ", "` varchar(1048576) ");
            body = INDEX.matcher(body).replaceAll(r -> r.group(1) + " `" + table + "__" + r.group(2) + "`");
            return "CREATE TABLE `" + table + "` (" + body + "\n)";
        }
        throw new IllegalArgumentException("No CREATE TABLE for " + table + " in db/n_industry");
    }

    private static String read(String path) {
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
