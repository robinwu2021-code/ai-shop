package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>每张表都要有人写它。</b>
 *
 * <h2>它是被 stl_payment 逼出来的</h2>
 * 2026-09-01 查明：{@code stl_payment} 从 {@code V1__baseline} 建起，
 * <b>生产代码里没有一处写过它</b>。连带空转的是两条巡检 ——
 * 收款对账轴查「停在 PENDING 的收款」，一行都没有，于是每轮报「没有差异」，
 * 而它本该发现的正是掉单。
 *
 * <p>更难发现的是：<b>相关测试一直是绿的</b>，因为它们自己插数据。
 * 逻辑被验证过、页面打得开、闸门全绿 —— 而真实链路根本不产生这种数据。
 *
 * <p>「这张表谁写」是建表当天最容易回答、事后最难查的问题。
 * 这条闸门把它挪到建表当天问。
 *
 * <h2>这是止血线，不是待办清单</h2>
 * 下面基线里的表<b>不都是欠账</b>，分两类：
 * <ul>
 *   <li>{@code SEEDED} —— 主数据，由迁移种子写入，代码只读。<b>这是正确的状态</b>，
 *       不需要「修」；</li>
 *   <li>{@code NO_PRODUCER} —— 确实没有生产者。其中有的是<b>有意让范围</b>
 *       （提现的 B 端入口，见 TDD-运营端财务补齐 §五 T5），
 *       有的没核实过，标了 {@code 未核实}。</li>
 * </ul>
 *
 * <p><b>不要把这份清单当成 TODO 逐条清掉</b> —— SEEDED 那半本来就该留着。
 * 它的作用是：<b>新表进来时必须在这里表态</b>，而不是像 stl_payment 那样
 * 悄悄躺着，直到有人去查一条巡检为什么从来不报警。
 */
class TableHasProducerTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Pattern TABLE = Pattern.compile("@TableName\\(\"([a-z_0-9]+)\"\\)");

    /** 主数据：由迁移种子写入，代码只读。**这是正确的状态**，不是欠账 */
    private static final List<String> SEEDED = List.of(
            "ful_carrier", "mch_admission_policy", "sys_channel_category_rule",
            "sys_function", "sys_function_point", "sys_industry", "sys_legal_form",
            "sys_merchant_plan_def", "sys_pay_channel");

    /**
     * 确实没有生产者的表。key 是表名，value 是<b>为什么</b>。
     *
     * <p>写清楚理由不是形式：一条没有理由的登记，下一个人只会照着再加一条，
     * 而清单一长就没人逐条读了 —— 那时它和没有清单是一样的。
     */
    private static final Map<String, String> NO_PRODUCER = new LinkedHashMap<>();

    static {
        // 已核实：有意让范围
        // stl_withdraw 已于 2026-09-02（V288）落地 B 端申请入口，从本表移除 —— 闸门自己报的陈行
        NO_PRODUCER.put("stl_settle_invoice",
                "同上 —— 商家申请结算发票的入口未做，运营侧的开票/驳回先落地");
        // 未核实：这条闸门立起来时就在库里，归属由各域自己填
        for (String t : List.of("cmt_community_apply", "cnt_post", "cnt_question", "inv_uom",
                "mch_channel_area", "mch_channel_pickup", "notify_scene_channel",
                "notify_template", "prd_merchant_spec", "prd_merchant_spec_value",
                "prd_store_goods", "prd_store_price")) {
            NO_PRODUCER.put(t, "未核实 —— 闸门立起来时就在库里。"
                    + "动到这个域时顺手确认：是主数据（挪进 SEEDED）、"
                    + "有意让范围（写明），还是真的漏了写入路径");
        }
    }

    @Test
    @DisplayName("★★★ 每张表都要有人写它 —— stl_payment 建了四个月没人写，两条巡检跟着空转")
    void everyTableHasSomethingThatWritesIt() throws IOException {
        Map<String, String> entities = scanEntities();
        String allSource = readAllProductionSource();
        List<String> seedSql = readMigrations();

        List<String> unregistered = new ArrayList<>();
        for (Map.Entry<String, String> e : entities.entrySet()) {
            String table = e.getKey();
            String cls = e.getValue();
            if (allSource.contains("new " + cls + "()")) {
                continue;   // 有生产者
            }
            if (seedSql.stream().anyMatch(sql -> sql.contains("INSERT INTO " + table))) {
                continue;   // 迁移种子写的
            }
            if (SEEDED.contains(table) || NO_PRODUCER.containsKey(table)) {
                continue;   // 已表态
            }
            unregistered.add(table + "（" + cls + "）");
        }

        /*
         * **对照量。** 这是「找出违规」型闸门 —— 扫不到实体时违规集是空的，
         * 与「都有生产者」长得一模一样。
         */
        assertThat(entities)
                .as("一个 @TableName 实体都没扫到 —— 判据失效了，那这条闸门量的是空气")
                .hasSizeGreaterThan(100);
        assertThat(allSource.length())
                .as("生产源码读进来是空的 —— 那「有生产者」会对每张表都不成立，反而全红")
                .isGreaterThan(100_000);

        assertThat(unregistered)
                .as("这些表没有任何东西写它，也没有在本测试里表态：\n  %s\n"
                        + "  这不一定是 bug —— 主数据本来就只读。但**必须说出是哪一种**：\n"
                        + "  · 迁移种子写的主数据 → 加进 SEEDED；\n"
                        + "  · 有意没做上游入口 → 加进 NO_PRODUCER 并写明理由与出处；\n"
                        + "  · 真的漏了 → 补上写入路径。\n"
                        + "  stl_payment 就是第三种：建了没人写，收款对账轴与 I8 跟着空转，\n"
                        + "  而相关测试一直是绿的（它们自己插数据）。", unregistered)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 登记表不能有死条目 —— 表没了或者已经有人写了，那条登记就该删")
    void registrationsMustStillBeTrue() throws IOException {
        Map<String, String> entities = scanEntities();
        String allSource = readAllProductionSource();

        List<String> stale = new ArrayList<>();
        for (String table : NO_PRODUCER.keySet()) {
            String cls = entities.get(table);
            if (cls == null) {
                stale.add(table + "：表已经不在了");
            } else if (allSource.contains("new " + cls + "()")) {
                stale.add(table + "：已经有生产者了，这条登记下面那句理由已经不成立");
            }
        }
        for (String table : SEEDED) {
            if (!entities.containsKey(table)) {
                stale.add(table + "：表已经不在了");
            }
        }

        assertThat(stale)
                .as("登记表里有陈行：\n  %s\n"
                        + "  留着的害处是它下面那句理由会变成谎话，"
                        + "而下一个读的人会照着它做判断。", stale)
                .isEmpty();
    }

    private static Map<String, String> scanEntities() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path module : modules()) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = TABLE.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    if (m.find()) {
                        String name = f.getFileName().toString();
                        out.put(m.group(1), name.substring(0, name.length() - ".java".length()));
                    }
                }
            }
        }
        return out;
    }

    private static String readAllProductionSource() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path module : modules()) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    sb.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static List<String> readMigrations() throws IOException {
        Path dir = BACKEND.resolve("shop-app/src/main/resources/db/migration");
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                out.add(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** 后端所有模块（含 pay/pay-domain 这种嵌套的）—— 少扫一个模块＝那个域的表全部免检 */
    private static List<Path> modules() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> top = Files.list(BACKEND)) {
            for (Path d : top.filter(Files::isDirectory).toList()) {
                out.add(d);
                try (Stream<Path> sub = Files.list(d)) {
                    sub.filter(Files::isDirectory).forEach(out::add);
                }
            }
        }
        return out;
    }
}
