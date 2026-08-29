package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 每个 {@code /ops} 端点都必须**明确**它要什么权限。
 *
 * <h2>为什么运营端此前没有这道闸</h2>
 * <p>B 端有 {@link BizEndpointPermTest}、C 端有 {@link MpEndpointAuthTest}、
 * 跨端有 {@code RealmIsolationTest} —— 唯独运营端没有。而它是
 * <b>端点最多（339）、动作最具破坏性</b>的一端：封禁商家、改结算、发券、
 * 调平台配置都在这里。2026-08-29 复核三端权限时发现的这处不对称。
 *
 * <h2>与 B 端那道闸的形状不同，是刻意的</h2>
 * <p>B 端把 207 个端点逐条列成 {@code REQUIRED} 表，因为那边当时大半端点还没注解，
 * 表本身就是「谁该拿什么」的设计稿。运营端不一样：**327/339 已经带注解**，
 * 再抄一张 327 行的表只会变成一份需要同步维护的影子清单 ——
 * 而影子清单迟早和代码对不上，那时人会信谁不好说。
 *
 * <p>所以这里反过来：**注解本身就是声明**，闸门只负责揪出「一句话都没说」的端点。
 * 白名单里只装那些**故意不要权限码**的（登录、找回密码、看自己的消息……），
 * 每一条都得写清为什么。
 *
 * <p>加新端点忘了注解、又没进白名单 → 直接红。<b>这道闸防的不是今天写错，
 * 是明天忘记。</b>
 */
class OpsEndpointPermTest {

    /**
     * ⚠️ 与 {@link BizEndpointPermTest} 同一个坑：这个正则**必须列全所有 HTTP 方法**。
     * 那边 2026-08-25 补 Patch 之前，任何 {@code @PatchMapping} 的端点都会整个绕过
     * 权限检查 —— 表里没有它、用例也不报，而线上真的能调。加一种方法就要来这里加一次。
     */
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Patch|Delete)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");

    /** 运营端的判权入口只有这两个：{@code @perm.can} 与 {@code @perm.canAny}。 */
    private static final Pattern GUARD = Pattern.compile("@perm\\.can(Any)?\\(");

    /** {@code Perms.XXX} 引用 —— 用来回查这个码在 Perms 里到底存不存在。 */
    private static final Pattern PERMS_REF = Pattern.compile("Perms\\.([A-Z_0-9]+)");

    /**
     * **故意不要权限码的端点。**
     *
     * <p>它们的共同点是「任何已登录的运营都该能用」——把它们塞进权限体系
     * 只会造出一批人人必授的码，而那种码等于没有码。
     *
     * <p>⚠️ 往这张表里加东西之前先问一句：**这个端点做的事，
     * 是不是所有运营（包括最低权限的客服）都应该能做？** 不是就别加，
     * 给它一个真正的码。这张表是例外，不是垃圾桶。
     */
    private static final Map<String, String> ANY_OPERATOR = new TreeMap<>(Map.ofEntries(
            Map.entry("/ops/auth/login", "登录本身。要权限才能登录是个死循环"),
            Map.entry("/ops/auth/forgot", "找回密码 —— 此时他还没有会话"),
            Map.entry("/ops/auth/reset", "重置密码，凭邮件/短信里的令牌，不凭会话权限"),
            Map.entry("/ops/captcha", "图形验证码，登录前就要取"),
            Map.entry("/ops/auth/me", "「我是谁」。前端拿它决定画哪些菜单，不能反过来要权限"),
            Map.entry("/ops/staffs/me/password", "改**自己**的密码。改别人的是 iam:staff:update，那条有码"),
            Map.entry("/ops/menu", "菜单本身就是按他的权限算出来的，再给它加一层码是循环"),
            Map.entry("/ops/message", "看**自己**的站内信"),
            Map.entry("/ops/message/unread-count", "自己的未读数"),
            Map.entry("/ops/message/{messageNo}/read", "把自己的信标已读"),
            Map.entry("/ops/message/read-all", "把自己的信全标已读"),
            Map.entry("/ops/stream", "SSE 推送通道。推什么由每条消息自己的权限决定，"
                    + "通道本身对所有登录运营开放")));

    @Test
    @DisplayName("★★★ 每个 /ops 端点都必须明确它要什么权限 —— 没说的直接报出来")
    void everyOpsEndpointHasADecision() throws IOException {
        Map<String, Boolean> endpoints = scanOpsEndpoints();
        assertThat(endpoints).as("一个端点都没扫到 —— 正则或目录结构变了？").isNotEmpty();

        Set<String> undecided = new TreeSet<>();
        endpoints.forEach((path, guarded) -> {
            if (!guarded && !ANY_OPERATOR.containsKey(path)) {
                undecided.add(path);
            }
        });

        assertThat(undecided)
                .as("这些 /ops 端点没有 @perm.can(...)，也没进 ANY_OPERATOR 白名单 —— "
                        + "每一个都是潜在的越权口子。\n"
                        + "要么给它一个权限码，要么加进白名单并写清为什么所有运营都能用：")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 白名单里不能有已经不存在的端点 —— 名单本身也会过期")
    void noStaleAllowlistEntries() throws IOException {
        Set<String> stale = new TreeSet<>(ANY_OPERATOR.keySet());
        stale.removeAll(scanOpsEndpoints().keySet());
        assertThat(stale)
                .as("这些端点已经没了，从 ANY_OPERATOR 删掉 —— 留着会让下一个人以为"
                        + "「这条已经想过了」，而它指的东西压根不存在")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 白名单与注解不能同时存在 —— 那说明有人改了主意却没删干净")
    void allowlistAndAnnotationAreMutuallyExclusive() throws IOException {
        Map<String, Boolean> endpoints = scanOpsEndpoints();
        List<String> both = ANY_OPERATOR.keySet().stream()
                .filter(p -> Boolean.TRUE.equals(endpoints.get(p)))
                .toList();
        assertThat(both)
                .as("这些端点既有 @perm.can 又在白名单里。**注解是执行的那一份**，"
                        + "白名单只会误导读的人以为它不要权限：")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 注解里引用的 Perms 常量必须真的存在 —— 写错一个字母就是永远 403")
    void referencedPermsExist() throws IOException {
        Set<String> declared = declaredPermConstants();
        assertThat(declared).as("没读到 Perms 常量，路径变了？").isNotEmpty();

        Set<String> bad = new TreeSet<>();
        for (Path p : opsSources()) {
            String src = Files.readString(p);
            String[] lines = src.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (!GUARD.matcher(lines[i]).find()) {
                    continue;
                }
                Matcher m = PERMS_REF.matcher(lines[i]);
                while (m.find()) {
                    if (!declared.contains(m.group(1))) {
                        bad.add(p.getFileName() + ": Perms." + m.group(1));
                    }
                }
            }
        }
        assertThat(bad)
                .as("Perms 里没有这些常量。**这类错误编译期就会挡住**，"
                        + "所以这条更多是防「有人改成字符串字面量」——"
                        + "那时写错一个字母，那个端点对所有人 403，"
                        + "而表现只是「按钮点了没反应」：")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 运营端不许用 hasAuthority —— 它匹配一个永远不存在的权威")
    void opsMustNotUseHasAuthority() throws IOException {
        /*
         * **这一条是这道闸第一次跑就抓到的东西。**
         *
         * OperatorTokenAuthFilter 只塞两类权威：ROLE_OPERATOR 与 ROLE_<角色>。
         * 权限码从来不进 authorities —— 它们由 @perm.can 每请求现算。
         * 所以 hasAuthority('finance:invoice:read') 精确匹配一个根本不存在的东西，
         * **对所有运营（含超管）永远 false**。
         *
         * 后果的形状是最坏的那种：编译通过、测试不报、日志无异常，
         * 页面上只表现为「点了没反应」。OpsInvoiceRequestController 的三个端点
         * （开票申请的列表 / 确认开票 / 驳回）就这么整个功能是死的。
         *
         * 单靠上面那条 everyOpsEndpointHasADecision 也能揪出它们（因为不带 @perm.can），
         * 但报出来的是「没有决定权限」——那会让人以为补个注解就行，
         * 而真正的问题是**写法本身在这一端无效**。所以单列一条，把话说准。
         */
        List<String> offenders = new java.util.ArrayList<>();
        for (Path p : opsSources()) {
            String src = Files.readString(p);
            if (src.contains("hasAuthority(")) {
                for (String line : src.split("\n")) {
                    if (line.contains("hasAuthority(") && line.contains("@PreAuthorize")) {
                        offenders.add(p.getFileName() + ": " + line.strip());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("运营端的判权入口只有 @perm.can / @perm.canAny。hasAuthority 在这一端"
                        + "**永远为 false** —— 权限码不进 authorities：")
                .isEmpty();
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    /** 路径 → 是否带判权注解。 */
    private static Map<String, Boolean> scanOpsEndpoints() throws IOException {
        Map<String, Boolean> out = new TreeMap<>();
        for (Path p : opsSources()) {
            String[] lines = Files.readString(p).split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = MAPPING.matcher(lines[i]);
                if (!m.find()) {
                    continue;
                }
                String path = m.group(1);
                if (!path.startsWith("/ops")) {
                    continue;
                }
                out.merge(path, guardedNear(lines, i), (a, b) -> a || b);
            }
        }
        return out;
    }

    /**
     * 注解在 {@code @XxxMapping} 的**上面还是下面都算**。
     *
     * <p>运营端的写法是写在下面，B 端是写在上面 —— 只往一个方向找，
     * 会得出「三百个端点裸奔」这种离谱结论。2026-08-29 复核时我就是这么错了一次。
     */
    private static boolean guardedNear(String[] lines, int at) {
        for (int j = at + 1; j < Math.min(lines.length, at + 8); j++) {
            if (GUARD.matcher(lines[j]).find()) {
                return true;
            }
            if (lines[j].strip().startsWith("public ") || lines[j].strip().startsWith("private ")) {
                break;   // 到方法签名了，注解块结束
            }
        }
        for (int j = at - 1; j >= Math.max(0, at - 8); j--) {
            if (GUARD.matcher(lines[j]).find()) {
                return true;
            }
            if (lines[j].strip().endsWith("}")) {
                break;   // 上一个方法结束了
            }
        }
        return false;
    }

    private static List<Path> opsSources() throws IOException {
        Path root = Path.of("..").toRealPath();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/test/"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .filter(OpsEndpointPermTest::mentionsOps)
                    .toList();
        }
    }

    private static boolean mentionsOps(Path p) {
        try {
            return Files.readString(p).contains("\"/ops");
        } catch (IOException e) {
            return false;
        }
    }

    private static Set<String> declaredPermConstants() throws IOException {
        Path perms = Path.of("..").toRealPath()
                .resolve("shop-base/src/main/java/ai/neargo/shop/auth/Perms.java");
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("String\\s+([A-Z_0-9]+)\\s*=")
                .matcher(Files.readString(perms));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
