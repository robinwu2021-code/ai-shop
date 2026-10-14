package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>往 JSON 列里种的值必须是合法 JSON。</b>
 *
 * <h2>这道闸拦的是一次生产上线失败</h2>
 * V288 往 {@code sys_pay_channel.pay_methods} 里写了裸字符串 {@code 'TEST_PAY'}，
 * 而生产表上有一条 CHECK 约束（MariaDB 的 {@code json_valid}）。
 * 结果是<b>整条迁移失败并留下一条 failed 记录，Flyway 因此拒绝启动 ——
 * 任何版本的 jar 都起不来</b>，不只是新的那个。只能先手工清记录再回滚。
 *
 * <p><b>本地 1641 条测试全绿</b>，因为测试库是 H2、schema 生成器不带 CHECK 约束过来。
 * jar 验过、启动冒烟也过了 —— 这三道都在「代码能不能跑」这一层，
 * 而这个错在「数据合不合法」那一层。
 *
 * <h2>判据</h2>
 * 迁移脚本里凡是往<b>已知的 JSON 列</b>插值的，那个值必须能被解析成 JSON
 * （或者是 NULL）。列名清单靠约定识别：现有 JSON 列都是复数名词
 * （{@code pay_methods} / {@code markets} / {@code qualifications}…），
 * 而它们在生产上都带 json_valid 约束。
 *
 * <p>只认<b>字面量</b>，不做表达式求值 —— 这道闸的价值在于
 * 「说出来的一定是真的」。
 */
class JsonColumnSeedTest {

    /** 生产上带 json_valid 约束的列。**加新的 JSON 列时要加到这里** */
    private static final List<String> JSON_COLUMNS = List.of(
            "pay_methods", "markets", "qualifications", "category_codes", "images");

    @Test
    @DisplayName("★★★ 种进 JSON 列的值必须是合法 JSON —— 否则生产迁移失败，Flyway 拒绝启动")
    void seedsIntoJsonColumnsMustBeValidJson() throws IOException {
        Path dir = Path.of("..", "shop-app", "src", "main", "resources", "db", "migration")
                .toRealPath();
        List<String> bad = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                String sql = Files.readString(f);
                // INSERT INTO t (cols) VALUES (vals), (vals)…
                Matcher ins = Pattern.compile(
                        "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*VALUES(.*?);",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
                while (ins.find()) {
                    List<String> cols = List.of(ins.group(2).split(","));
                    List<Integer> jsonIdx = new ArrayList<>();
                    for (int i = 0; i < cols.size(); i++) {
                        if (JSON_COLUMNS.contains(cols.get(i).trim().replace("`", ""))) {
                            jsonIdx.add(i);
                        }
                    }
                    if (jsonIdx.isEmpty()) {
                        continue;
                    }
                    for (String row : splitRows(ins.group(3))) {
                        List<String> vals = splitValues(row);
                        if (vals.size() != cols.size()) {
                            continue;   // 解析不准的行跳过，宁可漏报也不误报
                        }
                        for (int i : jsonIdx) {
                            scanned++;
                            String v = vals.get(i).trim();
                            if (v.equalsIgnoreCase("NULL") || v.isEmpty()) {
                                continue;
                            }
                            String inner = v.startsWith("'") && v.endsWith("'")
                                    ? v.substring(1, v.length() - 1) : v;
                            if (!looksLikeJson(inner)) {
                                bad.add(f.getFileName() + " · " + ins.group(1) + "."
                                        + cols.get(i).trim() + " = " + v);
                            }
                        }
                    }
                }
            }
        }

        // 扫描面断言：这道闸是「找出违规」型的，扫不到就报绿
        assertThat(scanned)
                .as("一个 JSON 列的种子值都没扫到 —— 多半是正则失配或目录变了。"
                        + "少扫比误报危险：它会安静地打勾")
                .isPositive();

        assertThat(bad)
                .as("这些值要写进 JSON 列，而它们不是合法 JSON。\n"
                        + "生产表上有 json_valid 约束，写进去<b>整条迁移会失败</b>，"
                        + "并留下一条 failed 记录 —— Flyway 因此拒绝启动，"
                        + "**任何版本的 jar 都起不来**，只能手工清记录再回滚。\n"
                        + "而测试库是 H2、不带 CHECK 约束，所以这类错本地永远发现不了。\n"
                        + "2026-09-02 的 V288 就是这么上线失败的（写了 'TEST_PAY' 而不是 '[\"TEST_PAY\"]'）。")
                .isEmpty();
    }

    /** 极简 JSON 判断：数组 / 对象 / 字符串字面量。**不引 JSON 库** —— 判据要一眼看得懂 */
    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return (t.startsWith("[") && t.endsWith("]"))
                || (t.startsWith("{") && t.endsWith("}"))
                || (t.startsWith("\"") && t.endsWith("\""));
    }

    /** 把 VALUES 后面的 `(...), (...)` 切成一行行 */
    private static List<String> splitRows(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            }
            if (inStr) {
                continue;
            }
            if (c == '(') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (c == ')' && --depth == 0 && start >= 0) {
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    /** 按顶层逗号切一行里的值 —— 引号内与括号内的逗号不算 */
    private static List<String> splitValues(String row) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inStr = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\'' && (i == 0 || row.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            }
            if (inStr) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(row.substring(start, i));
                start = i + 1;
            }
        }
        out.add(row.substring(start));
        return out;
    }
}
