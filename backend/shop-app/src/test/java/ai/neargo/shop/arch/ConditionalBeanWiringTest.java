package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>一个带开关的 bean，不许硬依赖另一个开关不同的 bean。</b>
 *
 * <h2>这条守卫是被一次「起不来」催出来的（2026-09-02）</h2>
 * <pre>
 * Parameter 0 of constructor in InventoryReconJob
 *   required a bean of type 'InventoryBackfillService' that could not be found
 * </pre>
 * {@code InventoryReconJob} 挂在 {@code shop.job.enabled} 上，而它构造依赖的
 * {@code InventoryBackfillServiceImpl} 挂在 {@code shop.inventory.enabled} 上。
 * 两个开关各开各的，于是 <b>job=true + inventory=false 这一格必然
 * APPLICATION FAILED TO START</b> —— 不是那一个任务不装配，是整个应用起不来。
 * {@code .claude/launch.json} 的 backend-ops 正好就是这一格。
 *
 * <h2>为什么不靠起上下文来测</h2>
 * 已经有 {@link InventoryDisabledContextTest} 那样起真上下文的闸门，但它<b>只测得到
 * 一格组合</b>：它设的是 inventory=false 而 job 留空，于是挂在 job 开关上的 bean
 * 在那个上下文里根本不装配，出事的那一格一次都没跑过。要靠起上下文覆盖全部组合，
 * 就得为每一格配齐它需要的基础设施（开 {@code shop.job.enabled} 就得配独立的
 * job 数据源，它<b>故意</b>不回退到平台库），N 个开关要 2^N 个上下文。
 *
 * <p>这里改成**读源码**：不起上下文、不要数据源，一次覆盖所有组合。
 * 代价是只看得见构造注入的直接依赖 —— 那恰好是「起不来」的绝大多数形状。
 *
 * <h2>判据</h2>
 * 依赖方的开关集合必须是被依赖方的<b>超集</b>。「A 开着时 B 一定开着」才安全；
 * 反过来 B 的条件比 A 严，就存在 A 装配而 B 缺席的那一格。
 */
class ConditionalBeanWiringTest {

    /** 后端各模块的源码根。测试与生成代码不看。 */
    private static final Path BACKEND = Paths.get("..").toAbsolutePath().normalize();

    /** `@ConditionalOnProperty(...)` 整块（可能跨行）。 */
    private static final Pattern COP = Pattern.compile(
            "@ConditionalOnProperty\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    /** 属性名：`name = "x"`、`name = {"x","y"}`、以及 `prefix = "p"` + `name = "n"` 两段式。 */
    private static final Pattern PREFIX = Pattern.compile("prefix\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NAMES = Pattern.compile("name\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    /** 类型声明与它实现的接口。 */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)([^{]*)\\{");
    /** `@ConditionalOnBean(X.class)` —— 它本身就把「X 不在就别装配我」表达清楚了。 */
    private static final Pattern ON_BEAN = Pattern.compile(
            "@ConditionalOnBean\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    /** 构造函数的参数表。 */
    private static final Pattern CTOR = Pattern.compile("public\\s+%s\\s*\\(([^)]*)\\)");

    /** 项目自己定义的组合注解 → 它等价的开关。手写是因为它就一个，解析注解定义得不偿失。 */
    private static final Map<String, String> ALIASES = Map.of(
            "@ConditionalOnInventory", "shop.inventory.enabled");

    /**
     * @param switches   这个 bean 装配所要求的开关
     * @param boolSwitch 它的条件是不是「布尔开关」（havingValue="true"）。
     *                   **只有布尔型才判违规**：策略选择型（provider=cos/local、
     *                   stock-authority=INVENTORY/DUAL）是同一属性下的多个实现，
     *                   属性取哪个值都有一个 bean 在，依赖方不会落空。
     */
    private record Bean(String file, String type, Set<String> switches, boolean boolSwitch,
                        List<String> ctorParamTypes, List<String> supertypes,
                        /** `@ConditionalOnBean` 点名的类型：它们缺席时这个 bean 自己也不装配 */
                        Set<String> guardedBy) { }

    @Test
    @DisplayName("★★★ 带开关的 bean 不许依赖开关更严的 bean —— 那一格整个应用起不来")
    void conditionalBeanDoesNotDependOnAStricterOne() throws IOException {
        List<Bean> beans = scan();
        assertThat(beans)
                .as("一个带 @ConditionalOnProperty 的 bean 都没扫到 —— 是扫描面塌了，不是没有违规")
                .hasSizeGreaterThan(10);

        // 类型名（含它实现的接口名）→ 提供该类型的实现所要求的开关
        Map<String, Set<String>> switchesOf = new LinkedHashMap<>();
        for (Bean b : beans) {
            if (!b.boolSwitch()) continue;
            switchesOf.put(b.type(), b.switches());
            for (String s : b.supertypes()) switchesOf.put(s, b.switches());
        }

        List<String> bad = new ArrayList<>();
        for (Bean b : beans) {
            for (String param : b.ctorParamTypes()) {
                Set<String> need = switchesOf.get(param);
                if (need == null || need.isEmpty()) continue;
                // @ConditionalOnBean 已经把这件事表达清楚了，开关声不声明都不会落空
                if (b.guardedBy().contains(param)) continue;
                if (!b.switches().containsAll(need)) {
                    Set<String> missing = new LinkedHashSet<>(need);
                    missing.removeAll(b.switches());
                    bad.add("%s（开关 %s）依赖 %s，而后者还要 %s —— 少了它的那一格起不来：%s"
                            .formatted(b.type(), b.switches(), param, missing, b.file()));
                }
            }
        }
        List<String> baseline = readBaseline();
        List<String> fresh = bad.stream().filter(b -> !baseline.contains(key(b))).toList();
        List<String> stale = baseline.stream()
                .filter(k -> bad.stream().noneMatch(b -> key(b).equals(k))).toList();

        assertThat(fresh)
                .as("给依赖方补上被依赖方的开关（@ConditionalOnProperty 的 name 收数组，全部匹配才装配），"
                        + "或用 @ConditionalOnBean 点名它")
                .isEmpty();
        assertThat(stale)
                .as("这些已经修好了，但基线里还留着 —— 留着等于给那处接线发了张永久免检的条子。\n"
                        + "  从 " + BASELINE + " 里删掉这些行。")
                .isEmpty();
    }

    /** 冻结清单：**只许变短**。 */
    private static final Path BASELINE = Paths.get("..", "known-conditional-wiring.txt");

    /**
     * 棘轮的键：{@code 依赖方 -> 被依赖方}。
     *
     * <p>**故意不含文件路径与开关名**：类挪个包、给它多加一个开关，都会让整条
     * 「已知」失配，于是变成一条新增违例 —— 而那种闸门会因为噪声被人整批重新生成，
     * 重新生成就等于清零。
     */
    private static String key(String violation) {
        String from = violation.substring(0, violation.indexOf('（'));
        String to = violation.substring(violation.indexOf("依赖 ") + 3);
        return from + " -> " + to.substring(0, to.indexOf('，'));
    }

    private static List<String> readBaseline() throws IOException {
        if (!Files.exists(BASELINE)) return List.of();
        return Files.readAllLines(BASELINE, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
    }

    private List<Bean> scan() throws IOException {
        List<Bean> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BACKEND)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Set<String> sw = switchesIn(src);
                if (sw.isEmpty()) continue;
                boolean bool = isBooleanSwitch(src);
                Set<String> guarded = onBeanTypes(src);
                Matcher td = TYPE_DECL.matcher(src);
                if (!td.find()) continue;
                String type = td.group(1);
                List<String> supers = supertypesIn(td.group(2));
                Matcher ctor = Pattern.compile(CTOR.pattern().formatted(type)).matcher(src);
                List<String> params = ctor.find() ? paramTypes(ctor.group(1)) : List.of();
                out.add(new Bean(BACKEND.relativize(p).toString(), type, sw, bool, params, supers, guarded));
            }
        }
        return out;
    }

    /** 类级别的开关。注解可能出现在字段/方法上，这里只取类声明之前的那一段。 */
    private static Set<String> switchesIn(String src) {
        Matcher td = TYPE_DECL.matcher(src);
        String head = td.find() ? src.substring(0, td.start()) : src;
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (head.contains(e.getKey())) out.add(e.getValue());
        }
        Matcher m = COP.matcher(head);
        while (m.find()) {
            String body = m.group(1);
            Matcher pm = PREFIX.matcher(body);
            String prefix = pm.find() ? pm.group(1) + "." : "";
            Matcher nm = NAMES.matcher(body);
            if (!nm.find()) continue;
            Matcher q = QUOTED.matcher(nm.group(1));
            while (q.find()) out.add(prefix + q.group(1));
        }
        return out;
    }

    /** 类声明之前出现的 @ConditionalOnProperty 是否全是 havingValue="true"。 */
    private static boolean isBooleanSwitch(String src) {
        Matcher td = TYPE_DECL.matcher(src);
        String head = td.find() ? src.substring(0, td.start()) : src;
        if (ALIASES.keySet().stream().anyMatch(head::contains)) return true;
        Matcher m = COP.matcher(head);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (!m.group(1).contains("havingValue = \"true\"")) return false;
        }
        return any;
    }

    private static Set<String> onBeanTypes(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ON_BEAN.matcher(src);
        while (m.find()) {
            Matcher q = Pattern.compile("(\\w+)\\.class").matcher(m.group(1));
            while (q.find()) out.add(q.group(1));
        }
        return out;
    }

    private static List<String> supertypesIn(String decl) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b(?:implements|extends)\\s+([^{]+)").matcher(decl);
        if (m.find()) {
            for (String t : m.group(1).split(",")) {
                String name = t.trim().replaceAll("<.*", "").trim();
                if (!name.isEmpty()) out.add(name.substring(name.lastIndexOf('.') + 1));
            }
        }
        return out;
    }

    private static List<String> paramTypes(String params) {
        List<String> out = new ArrayList<>();
        for (String raw : params.split(",")) {
            String t = raw.trim().replaceAll("^(final\\s+)?", "");
            // 泛型里逗号会把参数切碎，切碎的片段没有「类型 名字」两段，跳过即可
            String[] parts = t.split("\\s+");
            if (parts.length < 2) continue;
            out.add(parts[0].replaceAll("<.*", "").trim());
        }
        return out;
    }
}
