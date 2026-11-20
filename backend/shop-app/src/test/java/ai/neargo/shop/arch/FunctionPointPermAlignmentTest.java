package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营端的功能点登记必须对应本地鉴权端点，或已接线的跨系统菜单工作区。
 * 支付工作区的服务端鉴权由支付仓库验证；这里验证本仓菜单、页面引用与导航合同的接线。
 *
 * <h2>这两边错开的后果，各自是什么</h2>
 * <ul>
 *   <li><b>登记了、代码里没有</b> → 运营的权限配置界面上多出一条可以勾选、可以授权的能力，
 *       而它背后什么都没有。授了不报错，只是永远用不到 —— <b>界面在承诺一个不存在的功能</b>。</li>
 *   <li><b>代码里有、没登记</b> → 那个端点在配置界面里看不到、配不了。
 *       由于 {@code @perm.can} 认通配，持 {@code *} 的超管仍然能用 ——
 *       于是这个能力<b>事实上只有超管有，且不是通过「授予」得到的</b>，
 *       而这件事在任何界面上都看不出来。</li>
 * </ul>
 *
 * <h2>为什么查库而不是扫迁移 SQL</h2>
 * <p>试过，不成立：<b>迁移是历史，不是快照。</b>把 {@code sys_function_point} 的
 * INSERT 全抽出来会得到 124 个码，而实际终态是 97 —— 多出来的是早期那批粗码
 * （{@code audit:view}、{@code category:manage}…），它们在后续迁移里被改名或删掉了。
 * 按文本扫会把它们全算成「已登记」，于是这道闸从第一天起就报一堆假的。
 *
 * <p>测试库是把迁移按顺序重放一遍得到的，所以**查它就是查终态**。
 */
@SpringBootTest
@ActiveProfiles("test")
class FunctionPointPermAlignmentTest {

    @Autowired
    private JdbcClient jdbc;

    // Possessive repetitions consume long source tokens without a recursive backtracking stack.
    private static final String JAVA_STRING = "\"(?:[^\"\\\\]++|\\\\.)*+\"";
    private static final String JAVA_TEXT_BLOCK = "\"\"\"(?:[^\"\\\\]++|\\\\[\\s\\S]|\"(?!\"\"))*+\"\"\"";
    private static final String COMMENT = "//[^\\r\\n]*+|/\\*(?:[^*]++|\\*(?!/))*+\\*/";
    /** Consume strings, text blocks and comments so their contents cannot impersonate annotations. */
    private static final Pattern SOURCE_TOKEN = Pattern.compile(
            JAVA_TEXT_BLOCK + "|" + JAVA_STRING + "|" + COMMENT
                    + "|@PreAuthorize\\s*\\((?<authorization>(?:" + JAVA_STRING + "|[^\"()]++)*+)\\)"
                    + "|context\\.operator\\(\\s*\"(?<operator>[a-z][a-z:_-]*)\"\\s*\\)");
    private static final Pattern EXPRESSION_TOKEN = Pattern.compile(
            "(?<text>" + JAVA_STRING + ")|\\bPerms\\.(?<constant>[A-Z_0-9]+)");
    private static final Pattern GUARD = Pattern.compile("@perm\\.can(?:Any)?\\(([^)]*)\\)");
    private static final Pattern PERMISSION_ARGUMENT = Pattern.compile("'([a-z][a-z0-9:_-]*)'");

    /**
     * **已知欠账**，立闸门那天就存在的。只准变短，不准变长。
     *
     * <p>与 {@code backend/known-failures.txt} 同一个道理：要求「全部补齐才让过」
     * 会让它从第一天起恒红，而恒红的闸门等于没有闸门。
     */
    private static final Map<String, String> KNOWN_UNIMPLEMENTED = new TreeMap<>(Map.of(
            "member:person:merge",
            "「人工合并人档」已登记且授给了超管，但没有任何端点实现它。"
                    + "自动合并是有的（PersonServiceImpl 会写 UsrPersonMergeLog），"
                    + "而这个码的注释写的是「人工合并、不可逆」—— 那个入口不存在。"
                    + "2026-08-29 复核时发现，待产品决定：实现，还是把功能点下线。"));

    private static final Map<String, String> KNOWN_UNREGISTERED = new TreeMap<>(Map.of(
            "iam:role:read", "端点有且带注解，但没登记成功能点 —— 配置界面里看不到、配不了",
            "iam:staff:update", "同上。两条都靠超管的 * 通配兜住，"
                    + "即事实上只有超管能用，而这在界面上看不出来"));

    @Test
    @DisplayName("★★★ 登记的功能点须有本地端点或已接线的跨系统菜单工作区")
    void everyRegisteredPointHasAnEndpointOrNativePaymentPage() throws IOException {
        Set<String> registered = registeredPermCodes();
        assertThat(registered).as("一条功能点都没读到 —— 测试库没跑迁移？").isNotEmpty();

        Set<String> unimplemented = new TreeSet<>(registered);
        unimplemented.removeAll(permCodesUsedByEndpoints());
        unimplemented.removeAll(nativePaymentCenterPermCodes());
        unimplemented.removeAll(KNOWN_UNIMPLEMENTED.keySet());

        assertThat(unimplemented)
                .as("这些权限码登记成了功能点，运营能在界面上勾选、能授权，"
                        + "但既无本地端点，也不是支付中心原生页签（2026-11-20 起支付入口不再跳 near-pay 工作区，V686）。\n"
                        + "要么实现，要么把功能点下线 —— 留着等于让界面承诺一个做不到的事：")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 代码里用到的权限码应当登记成功能点 —— 没登记的只有超管能用，而且看不出来")
    void everyUsedPermIsRegistered() throws IOException {
        Set<String> unregistered = new TreeSet<>(permCodesUsedByEndpoints());
        unregistered.removeAll(registeredPermCodes());
        unregistered.removeAll(KNOWN_UNREGISTERED.keySet());

        assertThat(unregistered)
                .as("这些权限码有端点在用，但没登记成功能点 —— 在运营端的权限配置界面里"
                        + "**看不到、配不了**。由于 @perm.can 认通配，持 * 的超管仍然能用，"
                        + "于是这个能力事实上只有超管有，而这件事在任何界面上都看不出来：")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 欠账清单本身也会过期 —— 已经解决的必须从清单里删掉")
    void knownDebtIsStillReal() throws IOException {
        Set<String> registered = registeredPermCodes();
        Set<String> used = permCodesUsedByEndpoints();

        List<String> fixed = KNOWN_UNIMPLEMENTED.keySet().stream()
                .filter(used::contains).toList();
        assertThat(fixed)
                .as("这些码已经有端点实现了，从 KNOWN_UNIMPLEMENTED 删掉 —— "
                        + "**修好的行留在欠账清单里，那个对象就永远免检**")
                .isEmpty();

        List<String> nowRegistered = KNOWN_UNREGISTERED.keySet().stream()
                .filter(registered::contains).toList();
        assertThat(nowRegistered)
                .as("这些码已经登记成功能点了，从 KNOWN_UNREGISTERED 删掉：")
                .isEmpty();
    }

    // ── 两边的真值 ──────────────────────────────────────────────────────────

    /** 功能点登记的权限码 —— **查库**，因为迁移是历史不是快照。 */
    private Set<String> registeredPermCodes() {
        return new TreeSet<>(jdbc.sql("""
                        SELECT DISTINCT perm_code FROM sys_function_point
                         WHERE deleted = 0 AND perm_code IS NOT NULL AND perm_code <> ''
                        """)
                .query(String.class).list());
    }

    /** 代码里真的有端点在用的权限码（{@code Perms.X} → 字面量）。 */
    private Set<String> permCodesUsedByEndpoints() throws IOException {
        Map<String, String> literal = permLiterals();
        Set<String> out = new TreeSet<>();
        // V528 retired the electronic-components menus and grants after their
        // endpoints moved to ai-hxkey. Count only actual endpoints in this repo;
        // the external client's permission constants do not constitute handlers.
        for (Path p : sources()) {
            out.addAll(permCodesInSource(Files.readString(p), literal));
        }
        return out;
    }

    private static Set<String> permCodesInSource(String source, Map<String, String> literal) {
        Set<String> out = new TreeSet<>();
        for (Matcher token = SOURCE_TOKEN.matcher(source); token.find(); ) {
            if (token.group("operator") != null) {
                out.add(token.group("operator"));
            } else if (token.group("authorization") != null) {
                StringBuilder expression = new StringBuilder();
                for (Matcher part = EXPRESSION_TOKEN.matcher(token.group("authorization")); part.find(); ) {
                    String text = part.group("text");
                    if (text != null) {
                        expression.append(text.substring(1, text.length() - 1).translateEscapes());
                    } else {
                        String name = part.group("constant");
                        assertThat(literal).as("PreAuthorize 引用了无法解析的 Perms.%s", name).containsKey(name);
                        expression.append(literal.get(name));
                    }
                }
                for (Matcher guard = GUARD.matcher(expression); guard.find(); ) {
                    for (Matcher argument = PERMISSION_ARGUMENT.matcher(guard.group(1)); argument.find(); ) {
                        out.add(argument.group(1));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void permissionScannerReadsMultilineConstantsAndLiteralCanArguments() {
        String source = """
                @PreAuthorize(
                    "@perm.can('" + Perms.FIRST + "') or @perm.canAny('literal:read', '"
                    + Perms.SECOND + "')")
                public Object read() { return context.operator("commerce:catalog:read"); }
                """;
        assertThat(permCodesInSource(source, Map.of("FIRST", "first:read", "SECOND", "second:read")))
                .containsExactlyInAnyOrder("first:read", "second:read", "literal:read", "commerce:catalog:read");
    }

    @Test
    void permissionScannerRejectsCommentsStringsAndUnrelatedAuthorizationArguments() {
        String source = """
                // @PreAuthorize("@perm.can('comment:read')")
                /* context.operator("comment:operator") */
                String sample = "@PreAuthorize(\\"@perm.can('sample:read')\\")";
                @PreAuthorize("hasAuthority('unrelated:read') and #request.permission() == 'not:guarded'")
                public void read() {}
                """;
        assertThat(permCodesInSource(source, Map.of())).isEmpty();
    }

    @Test
    void permissionScannerConsumesLargeStringsTextBlocksAndCommentsWithoutRecursion() {
        String padding = "x".repeat(100_000);
        String string = "String sample = \"" + padding + "\\\"".repeat(20_000) + "\";\n";
        String textBlock = "String text = \"\"\"\n" + padding
                + "\n@PreAuthorize(\"@perm.can('text:block')\")\n\"\"\";\n";
        String comments = "/*" + "x*".repeat(100_000)
                + "@PreAuthorize(\"@perm.can('comment:block')\")*/\n"
                + "//" + padding + "context.operator(\"comment:line\")\n";
        String authorization = "@PreAuthorize(\"#request.note() == '" + padding
                + "' and @perm.can('real:read')\")\npublic void read() {}";
        assertThat(permCodesInSource(string + textBlock + comments + authorization, Map.of()))
                .containsExactly("real:read");
        assertThat(withoutComments(string + textBlock + comments))
                .isEqualTo(string + textBlock + " \n \n");
    }

    private record RemoteMenu(String href, String permission) {}

    /**
     * 支付中心原生页（TDD-支付中心运营页面、V684）：页面在运营端里直接调 near-pay `/pay-ops/v2`，
     * 端点在 near-pay，不在本仓库。认定条件三者同时成立，缺一不算：
     * ① 菜单点位 href = `/payment-center?tab=<key>`；② `center-tabs.ts` 登记了这个页签且权限码与点位一致；
     * ③ `app/payment-center/page.tsx` 的 TAB_COMPONENT 真的渲染这个页签。
     */
    private Set<String> nativePaymentCenterPermCodes() throws IOException {
        Path repository = Path.of("../..").toRealPath();
        String tabs = withoutComments(Files.readString(repository.resolve("ops-web/app/payment-center/module.ts")));
        Map<String, String> permOfTab = new TreeMap<>();
        Matcher tab = Pattern.compile("\\{\\s*key:\\s*\"([a-z-]+)\",\\s*perm:\\s*\"([a-z][a-z0-9:_-]*)\"").matcher(tabs);
        while (tab.find()) permOfTab.put(tab.group(1), tab.group(2));
        assertThat(permOfTab).as("PAY_CENTER_TABS 未读到").isNotEmpty();
        String page = withoutComments(Files.readString(repository.resolve("ops-web/app/payment-center/page.tsx")));
        Matcher table = Pattern.compile("TAB_COMPONENT[^=]*=\\s*\\{(.*?)\\};", Pattern.DOTALL).matcher(page);
        assertThat(table.find()).as("payment-center/page.tsx 的 TAB_COMPONENT 未读到").isTrue();
        Set<String> rendered = new TreeSet<>();
        Matcher key = Pattern.compile("\"?([a-z-]+)\"?:\\s*[A-Z]\\w*Tab\\b").matcher(table.group(1));
        while (key.find()) rendered.add(key.group(1));
        List<RemoteMenu> menus = jdbc.sql("""
                SELECT href, perm_code FROM sys_function_point
                 WHERE deleted=0 AND point_type='MENU' AND href LIKE '/payment-center?tab=%' AND perm_code IS NOT NULL
                """).query((rs, row) -> new RemoteMenu(rs.getString("href"), rs.getString("perm_code"))).list();
        Set<String> out = new TreeSet<>();
        for (RemoteMenu menu : menus) {
            String k = menu.href().substring("/payment-center?tab=".length());
            if (rendered.contains(k) && menu.permission().equals(permOfTab.get(k))) out.add(menu.permission());
        }
        return out;
    }

    private static String withoutComments(String source) {
        return Pattern.compile(JAVA_TEXT_BLOCK + "|" + JAVA_STRING + "|" + COMMENT).matcher(source)
                .replaceAll(match -> match.group().startsWith("/") ? " " : Matcher.quoteReplacement(match.group()));
    }


    /** {@code Perms} 的常量名 → 字面量。 */
    private static Map<String, String> permLiterals() throws IOException {
        Path perms = Path.of("..").toRealPath()
                .resolve("shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java");
        Map<String, String> out = new TreeMap<>();
        Matcher m = Pattern.compile("String\\s+([A-Z_0-9]+)\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(perms));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static List<Path> sources() throws IOException {
        Path root = Path.of("..").toRealPath();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/test/"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .toList();
        }
    }
}
