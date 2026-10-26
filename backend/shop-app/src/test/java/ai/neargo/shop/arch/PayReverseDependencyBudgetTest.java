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

    /*
     * ── 扫全部 spi Port（2026-10-25，TDD-支付服务-I0地基 AC7，修 Review R28）──────────
     * 上面两条只盯基线里写着的 Port：一个**新**的业务侧 Port 第一次被 pay 引用时，
     * 它不在基线里，就没人数它 —— 预算从「只准变少」悄悄变成「只管老的」。
     * 下面按**实现所在位置**自动认方向：只在 backend/pay 之外有实现的 spi Port，就是 pay → 业务的反向依赖。
     */
    private static final Path BACKEND = Path.of("..");
    private static final Path SPI = Path.of("..", "shop-base", "src", "main", "java", "ai", "neargo", "shop", "spi");
    private static final Pattern PORT_DECL = Pattern.compile("\\binterface\\s+(\\w+Port)\\b");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\s+([^{]+)\\{");

    /** spi 里声明的全部 Port 简名 */
    private static java.util.Set<String> spiPorts() throws IOException {
        java.util.Set<String> ports = new java.util.TreeSet<>();
        try (Stream<Path> files = Files.walk(SPI)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = PORT_DECL.matcher(codeOf(f));
                while (m.find()) {
                    ports.add(m.group(1));
                }
            }
        }
        return ports;
    }

    /** Port 简名 → 实现所在的两类位置（"pay" / "other"） */
    private static Map<String, java.util.Set<String>> implementationSides(java.util.Set<String> ports) throws IOException {
        Map<String, java.util.Set<String>> sides = new java.util.HashMap<>();
        try (Stream<Path> files = Files.walk(BACKEND)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                String side = BACKEND.relativize(f).toString().startsWith("pay/") ? "pay" : "other";
                var m = IMPLEMENTS.matcher(codeOf(f));
                while (m.find()) {
                    for (String port : ports) {
                        if (Pattern.compile("\\b" + port + "\\b").matcher(m.group(1)).find()) {
                            sides.computeIfAbsent(port, k -> new java.util.HashSet<>()).add(side);
                        }
                    }
                }
            }
        }
        return sides;
    }

    /** 被 pay 引用、且只在 pay 之外实现的 Port，及其在 pay 里的引用数 */
    static Map<String, Integer> reversePortsReferencedFromPay() throws IOException {
        var ports = spiPorts();
        var sides = implementationSides(ports);
        Map<String, Integer> candidates = new LinkedHashMap<>();
        for (String port : ports) {
            var s = sides.getOrDefault(port, java.util.Set.of());
            if (s.equals(java.util.Set.of("other"))) {
                candidates.put(port, 0);
            }
        }
        Map<String, Integer> referenced = new java.util.TreeMap<>();
        actual(candidates).forEach((port, n) -> {
            if (n > 0) {
                referenced.put(port, n);
            }
        });
        return referenced;
    }

    @Test
    @DisplayName("★★★ 每个被 pay 引用的业务侧 Port 都在预算里 —— 新出现的那个不能因为「不在名单上」就没人数")
    void everyReversePortIsBudgeted() throws IOException {
        var base = baseline();
        var now = reversePortsReferencedFromPay();

        assertThat(spiPorts()).as("spi 里一个 Port 都没认出来 —— 路径错了？").hasSizeGreaterThan(50);
        assertThat(now).as("一个反向 Port 都没认出来 —— 判据失效（MerchantQueryPort 至少该在）")
                .containsKey("MerchantQueryPort");

        var unbudgeted = new java.util.TreeMap<String, Integer>();
        now.forEach((port, n) -> {
            if (!base.containsKey(port)) {
                unbudgeted.put(port, n);
            }
        });
        assertThat(unbudgeted)
                .as("这些 Port 只在支付之外实现、却被 pay 引用，且不在 backend/known-pay-reverse-deps.txt：%s\n"
                        + "  每一个都是 pay-svc 独立那天要补的 HTTP 客户端。确实要依赖就登记进去并写明为什么；\n"
                        + "  能由 pay 自己持有的事实，就别经 Port 去业务侧取。", unbudgeted)
                .isEmpty();
    }
}
