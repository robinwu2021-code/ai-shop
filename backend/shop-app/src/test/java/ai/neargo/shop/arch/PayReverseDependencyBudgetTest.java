package ai.neargo.shop.arch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pay 域对业务侧 Port 的引用数 —— <b>只准变少</b>（S19/M11 的前置测量）。
 *
 * <h2>它纠正的是一句错话</h2>
 * 优化清单里写着「M11（保证金欠款搬进 pay 库）是 pay-svc 独立后<b>最后一条</b>
 * 反向依赖」。2026-09-02 数了一遍：<b>那句话不对</b>。
 *
 * <p>M11 能消掉的只有 {@code fundRiskFacts} <b>一处</b>调用。
 * 其余 40 处是主体属性（资金模式、经营模式、法律形态、市场、积分开关…）
 * 与订单来源 —— 搬四张表一处都动不了。
 *
 * <p>这个差别决定要不要现在做：<b>M11 不是解锁项</b>，
 * 做完 pay-svc 还是接不了流量。它是 D2（独立库）之前的归属整理，
 * 而 D2 本身是被推后的。
 *
 * <h2>方向要分清</h2>
 * {@code PointsPort} / {@code MarketPort} / {@code PayChannelMasterPort}
 * 也出现在 pay 里，但那是 pay <b>实现</b>给业务用的（业务 → pay），
 * 方向正确，不在这份预算里。把它们算进来会让这个数虚高一倍，
 * 而虚高的数字会让「还差很多」变成一句无法证伪的话。
 */
class PayReverseDependencyBudgetTest {

    private static final Path BASELINE = Path.of("..", "known-pay-reverse-deps.txt");
    private static final Path PAY_ROOT = Path.of("..", "pay");

    /** 块注释与行注释都剔掉 —— 解释这条规则的那句话本身不该把数顶上去 */
    private static String codeOf(Path f) throws IOException {
        String txt = Files.readString(f);
        txt = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(txt).replaceAll("");
        return txt.replaceAll("//.*", "");
    }

    private static Map<String, Integer> baseline() throws IOException {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(BASELINE)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            m.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }
        return m;
    }

    private static Map<String, Integer> actual(Map<String, Integer> ports) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        ports.keySet().forEach(p -> counts.put(p, 0));
        try (Stream<Path> files = Files.walk(PAY_ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/src/test/"))
                    .toList()) {
                String code = codeOf(f);
                for (String port : counts.keySet()) {
                    var m = Pattern.compile("\\b" + port + "\\b").matcher(code);
                    int n = 0;
                    while (m.find()) {
                        n++;
                    }
                    counts.merge(port, n, Integer::sum);
                }
            }
        }
        return counts;
    }

    @Test
    @DisplayName("★★★ 反向依赖只准变少 —— 多一处，pay-svc 独立那天就要多写一个 HTTP 客户端")
    void reverseDepsNeverGrow() throws IOException {
        var base = baseline();
        var now = actual(base);

        // 扫描面断言：一个都没数到的话，下面每一条都会「通过」
        assertThat(now.values().stream().mapToInt(Integer::intValue).sum())
                .as("一处都没数到 —— 多半是路径错了，而那样这条闸门永远绿")
                .isGreaterThan(0);

        base.forEach((port, allowed) -> assertThat(now.get(port))
                .as(port + " 的反向引用从 " + allowed + " 涨到了 " + now.get(port) + "。\n"
                        + "每多一处，pay-svc 独立那天就要多写一个 HTTP 客户端，"
                        + "而在那之前那个进程装得起来、用不了。\n"
                        + "确实必须加的话，改 backend/known-pay-reverse-deps.txt 并写明为什么。")
                .isLessThanOrEqualTo(allowed));
    }

    @Test
    @DisplayName("★★★ 基线不许锈 —— 修好了不减数，那几处就永远免检")
    void baselineDoesNotRust() throws IOException {
        var base = baseline();
        var now = actual(base);

        base.forEach((port, allowed) -> assertThat(now.get(port))
                .as(port + " 实际只剩 " + now.get(port) + " 处，而基线还写着 " + allowed + "。\n"
                        + "**基线只准变短**：不减的话，中间那几处永远免检 —— "
                        + "一边减一边加，这条闸门一次都不会红。\n"
                        + "把 backend/known-pay-reverse-deps.txt 改成实际值。")
                .isEqualTo(allowed));
    }

    @Test
    @DisplayName("★★★ M11 只能消掉一处 —— 「最后一条反向依赖」那句话是错的")
    void m11RemovesExactlyOneCallSite() throws IOException {
        int calls = 0;
        try (Stream<Path> files = Files.walk(PAY_ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/src/test/"))
                    .toList()) {
                var m = Pattern.compile("fundRiskFacts\\s*\\(").matcher(codeOf(f));
                while (m.find()) {
                    calls++;
                }
            }
        }
        assertThat(calls)
                .as("fundRiskFacts 的调用点数变了。这个数是判断「M11 值不值得现在做」的依据："
                        + "它是保证金欠款那四张表在 pay 侧的**全部**接触面，"
                        + "而反向依赖总共 41 处 —— 搬表解不开另外 40 处")
                .isEqualTo(1);
    }
}
