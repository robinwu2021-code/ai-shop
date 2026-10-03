package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>标了 {@code embedded} 的服务，必须有 {@code standalone} 的对应物。</b>
 *
 * <h2>这道闸拦的是一次真实的生产启动失败</h2>
 * 2026-09-02 部署时 shop-app 起不来：
 * {@code OpsPayChannelController} 要 {@code OpsPayChannelAppService}，
 * 而它的唯一实现挂着 {@code @ConditionalOnProperty(shop.pay.deployment=embedded)} ——
 * <b>而生产的那个值是 standalone</b>。于是没有任何 bean，
 * Spring 上下文起不来，服务反复重启。
 *
 * <p><b>本地 1632 个测试全绿。</b>因为测试跑的是默认值
 * （{@code matchIfMissing = true} → embedded），
 * 而<b>生产跑的是另一半</b>。
 * 「默认开着的那一半有测试，默认关着的那一半没人测」——
 * 而生产偏偏是关着的那一半。
 *
 * <h2>判据</h2>
 * 扫 {@code payclient} 下所有带 {@code havingValue = "embedded"} 的类，
 * 取它实现的接口名；同一个接口必须另有一个带
 * {@code havingValue = "standalone"} 的实现。
 *
 * <p>只认注解字面量，不做上下文推断 —— 这道闸的价值在于
 * 「说出来的一定是真的」，而不在于覆盖每一种写法。
 */
class PayDeploymentModePairingTest {

    /**
     * 只认<b>行首的真注解</b>，不认注释里引述的那一句。
     *
     * <p>第一版没有 {@code ^\\s*} 这个锚，于是这个类自己的 javadoc 里
     * 引述旧写法的那行被当成了真注解 —— <b>修完之后闸门照旧报红</b>，
     * 而它指的是一段解释文字。「守卫也扫注释」，
     * 而解释规则的那句话本身要能通过规则。
     */
    private static final Pattern COND = Pattern.compile(
            "^\\s*@ConditionalOnProperty\\([^)]*havingValue\\s*=\\s*\"(embedded|standalone)\"",
            Pattern.MULTILINE);
    private static final Pattern IMPLEMENTS = Pattern.compile(
            "class\\s+\\w+\\s+implements\\s+([\\w.]+)");

    @Test
    @DisplayName("★★★ embedded 的实现必须配一个 standalone 的 —— 生产跑的是后者")
    void embeddedImplNeedsStandalonePeer() throws IOException {
        Path root = Path.of("..", "shop-app", "src", "main", "java",
                "ai", "neargo", "shop", "payclient").toRealPath();

        Map<String, Set<String>> byInterface = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher c = COND.matcher(src);
                if (!c.find()) {
                    continue;
                }
                Matcher i = IMPLEMENTS.matcher(src);
                if (!i.find()) {
                    continue;
                }
                String iface = i.group(1).substring(i.group(1).lastIndexOf('.') + 1);
                byInterface.computeIfAbsent(iface, k -> new TreeSet<>()).add(c.group(1));
            }
        }

        // 扫描面断言：这道闸是「找出违规」型的，扫不到就报绿
        assertThat(byInterface)
                .as("一个带部署形态条件的实现都没扫到 —— 多半是包挪了或注解写法变了。"
                        + "少扫比误报危险：它会安静地打勾")
                .isNotEmpty();

        List<String> lonely = byInterface.entrySet().stream()
                .filter(e -> e.getValue().contains("embedded") && !e.getValue().contains("standalone"))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(lonely)
                .as("这些服务只有 embedded 实现，没有 standalone 的。\n"
                        + "**生产的 shop.pay.deployment 就是 standalone** —— "
                        + "上线那一刻没有任何 bean，Spring 上下文起不来，服务反复重启。\n"
                        + "而本地测试全绿，因为 matchIfMissing=true 让测试跑的是 embedded 那一半。\n"
                        + "2026-09-02 真撞过一次：部署完 health 五分钟不到 200，只能回滚。\n"
                        + "要么补一个 Remote 实现，要么摘掉这个条件（D2 切库前两种形态共用一个库，摘掉是安全的）。")
                .isEmpty();
    }
}
