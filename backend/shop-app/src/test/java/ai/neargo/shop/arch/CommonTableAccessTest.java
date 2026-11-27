package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 基础能力库的表只能经各组唯一的实现访问（TDD-基础能力库对齐 验收 K1、§2.2 第 1 阶段 1a）。
 *
 * <p>八组公共能力（权限、开放应用、事件、字典、审批、发号、审计、设置）各收敛成一个实现之后，才能给它一个句柄、按配置换库。
 * 换库前凡是绕过唯一实现、直接碰这些表的代码，都会去主库读旧表 —— 不报错，读到的是另一份数据。
 *
 * <p>「访问」有两种：字符串字面量（手写 SQL、表名常量）里出现该组的旧表名；或者用到映射这些表的实体类、Mapper。
 * 实体类自己的 {@code @TableName} 与 Mapper 的 {@code BaseMapper<实体>} 声明是定义，不算访问。
 * 表名清单取自 {@code docs/technical/design/数据库设计/库/02-基础能力库.md} 各组的「来源」与「并入了哪些旧表」。
 *
 * <p>结果与 {@code backend/known-common-table-access.txt}（每行「组 文件」）比，只准变短。
 */
class CommonTableAccessTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();
    private static final Path KNOWN = BACKEND.resolve("known-common-table-access.txt");

    /** 映射旧表的类：所在包，嵌套在哪个外部类里（顶层类为 null）。 */
    private record TypeRef(String group, String pkg, String name, String outer) {

        /** 这个文件引用的同名类是不是它：同包、import 了它（或它的外部类、整个包）、或写成 外部类.名。 */
        boolean visibleIn(String code) {
            String head = pkg + "." + (outer != null ? outer : name);
            return pkg.equals(packageOf(code)) || code.contains("import " + head + ";")
                    || code.contains("import " + head + ".") || code.contains("import " + pkg + ".*;")
                    || (outer != null && code.contains(outer + "." + name)) || code.contains(pkg + "." + name);
        }
    }

    private record Group(List<String> tables, List<String> implementation) {
    }

    /** 每组的旧表与唯一实现（路径前缀，相对 backend/）。没有集中处的组，实现为空：所有访问都在基线里。 */
    private static final Map<String, Group> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("权限", new Group(List.of("sys_api_resource", "sys_api_resource_point", "sys_function",
                "sys_function_point", "sys_role", "sys_role_member", "sys_role_point"),
                List.of("shop-core/src/main/java/ai/neargo/shop/platform/perm/")));
        GROUPS.put("开放应用", new Group(List.of("pay_app", "pay_app_grant", "inv_open_credential"), List.of()));
        GROUPS.put("事件", new Group(List.of("sys_outbox", "sys_outbox_stream", "sys_outbox_delivery",
                "pay_biz_notify_attempt", "sys_outbox_operation", "sys_event_consumed", "pay_approval_event",
                "pay_biz_notify", "pay_ent_inbox", "pay_ent_outbox", "pts_inbox", "pts_outbox", "cim_inbound_event",
                "cim_operation", "commerce_event_inbox", "commerce_outbox", "inv_event_consumed", "inv_outbox",
                "inv_outbox_delivery", "inv_outbox_operation", "inv_outbox_stream", "lgs_push_event",
                "ord_points_source_fact", "prn_store_stream"),
                List.of("shop-store-mybatis/src/main/java/ai/neargo/shop/event/",
                        "shop-store-mybatis/src/main/java/ai/neargo/shop/idem/")));
        GROUPS.put("字典", new Group(List.of("pay_channel", "pay_channel_product", "sys_pay_channel",
                "sys_pay_channel_market", "cim_product_profile", "cim_provider", "inv_uom", "sys_auth_code",
                "sys_business_type", "sys_industry", "sys_legal_form", "sys_market", "sys_region", "sys_store_industry"),
                // 端口 DictionaryPort 的唯一实现与它按类型汇集的来源（TDD-基础能力库对齐 §2.2 1b）
                List.of("shop-core/src/main/java/ai/neargo/shop/platform/port/DictionaryPortImpl.java",
                        "shop-core/src/main/java/ai/neargo/shop/platform/port/OnboardIndustryDictionarySource.java",
                        "shop-merchant/src/main/java/ai/neargo/shop/merchant/port/StoreIndustryDictionarySource.java",
                        "pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/port/PayChannelDictionarySource.java")));
        GROUPS.put("审批", new Group(List.of("approval_link", "sys_approval", "pay_terminal_manager_authorization",
                "ord_manual_price_adjustment"),
                List.of("shop-opskit/src/main/java/ai/neargo/shop/approval/opskit/ApprovalLinkStore.java",
                        // 轻量审批 sys_approval 的唯一实现（TDD-基础能力库对齐 §2.2 1e）
                        "shop-core/src/main/java/ai/neargo/shop/platform/port/LightApprovalPortImpl.java")));
        // mkt_counter_campaign_fence 不算：它是营销活动的并发围栏（同事务行锁），不是计数，留在会员营销（TDD 1d 盘点）
        GROUPS.put("发号", new Group(List.of("sys_sequence", "pay_checkout_display_revision", "pay_document_sequence",
                "pay_sequence", "core_document_number", "pay_document_number"),
                List.of("shop-store-mybatis/src/main/java/ai/neargo/shop/numbering/")));
        GROUPS.put("审计", new Group(List.of("pay_operation_audit", "pay_merchant_status_log",
                "pay_pos_configuration_audit", "sys_audit_log"),
                List.of("shop-core/src/main/java/ai/neargo/shop/platform/port/AuditLogPortImpl.java",
                        "shop-core/src/main/java/ai/neargo/shop/platform/impl/AuditLogServiceImpl.java")));
        GROUPS.put("设置", new Group(List.of("sys_setting", "pay_setting", "sys_channel_category_rule"),
                List.of("shop-core/src/main/java/ai/neargo/shop/platform/impl/SettingServiceImpl.java")));
    }

    /** 提到表名但不是访问：数据域按表名登记；已执行的历史迁移（冻结）。 */
    private static final List<String> NOT_ACCESS = List.of(
            "shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java", "/src/main/java/db/migration/");

    /**
     * 旧表名同时是业务表的列名：{@code pay_channel} 是订单、收款记录上的「付款渠道」列，出现在 {@code @TableField}、
     * 取列值的地方都不是访问那张旧表（旧表本身本仓库没有读写代码）。
     */
    private static final Set<String> COLUMN_NAMED = Set.of("pay_channel");
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|into|update|table)\\s+`?([a-z]+_[a-z_]+)\\b");
    private static final Pattern LITERAL = Pattern.compile("(?s)\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern TABLE_NAME = Pattern.compile("@TableName\\(\\s*(?:value\\s*=\\s*)?\"(\\w+)\"[^)]*\\)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern CLASS = Pattern.compile("\\b(?:class|record)\\s+(\\w+)");
    private static final Pattern DECLARED = Pattern.compile("\\b(?:class|record|interface|enum)\\s+(\\w+)");
    private static final Pattern MAPPER = Pattern.compile(
            "interface\\s+(\\w+)\\s+extends\\s+BaseMapper<\\s*(?:[\\w.]+\\.)?(\\w+)\\s*>");

    @Test
    @DisplayName("基础能力库的表只能经各组唯一实现访问：越界清单只准变短")
    void common_tables_are_reached_only_through_their_single_implementation() throws IOException {
        Map<String, String> tableGroup = new HashMap<>();
        GROUPS.forEach((group, g) -> g.tables().forEach(t -> tableGroup.put(t, group)));

        Map<Path, String> code = new LinkedHashMap<>();
        for (Path file : sources()) {
            code.put(file, Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", ""));
        }
        // 映射旧表的实体类与 Mapper：用到它们就是访问。同名类不少（如会员域也有 SettingMapper），按包与外部类区分
        Map<String, String> entityGroup = new HashMap<>();
        Map<String, List<TypeRef>> types = new HashMap<>();
        for (String c : code.values()) {
            Matcher t = TABLE_NAME.matcher(c);
            Matcher cls = CLASS.matcher(c);
            if (t.find() && tableGroup.containsKey(t.group(1)) && cls.find(t.end())) {
                String group = tableGroup.get(t.group(1));
                entityGroup.put(cls.group(1), group);
                types.computeIfAbsent(cls.group(1), k -> new ArrayList<>())
                        .add(new TypeRef(group, packageOf(c), cls.group(1), null));
            }
        }
        for (String c : code.values()) {
            Matcher m = MAPPER.matcher(c);
            Matcher top = DECLARED.matcher(c);
            String outer = top.find() ? top.group(1) : null;
            while (m.find()) {
                if (entityGroup.containsKey(m.group(2))) {
                    types.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(new TypeRef(entityGroup.get(m.group(2)),
                            packageOf(c), m.group(1), m.group(1).equals(outer) ? null : outer));
                }
            }
        }

        Set<String> actual = new TreeSet<>();
        for (var e : code.entrySet()) {
            String path = BACKEND.relativize(e.getKey()).toString();
            if (NOT_ACCESS.stream().anyMatch(path::contains)) {
                continue;
            }
            // 定义不算访问：实体自己的表名注解、Mapper 的泛型声明、import 行、本文件声明的类型
            String c = MAPPER.matcher(TABLE_NAME.matcher(e.getValue()).replaceAll("")).replaceAll("")
                    .replaceAll("(?m)^import\\s+[^;]+;", "");
            Set<String> declared = new HashSet<>();
            Matcher decl = DECLARED.matcher(c);
            while (decl.find()) {
                declared.add(decl.group(1));
            }
            Set<String> groups = new TreeSet<>();
            // 字面量里的表名：整个字面量就是表名（表名常量、拼 SQL 用的表名），或者在 SQL 的表位置上
            // （FROM/JOIN/INTO/UPDATE/TABLE 之后）。旧表名又是业务表列名的（见 COLUMN_NAMED），只认表位置。
            // 相邻字面量拼成一段再看，"... FROM " + "sys_region" 这种拆行写法也认得
            StringBuilder sql = new StringBuilder();
            Matcher literal = LITERAL.matcher(c);
            while (literal.find()) {
                String text = literal.group(1) != null ? literal.group(1) : literal.group(2);
                String group = COLUMN_NAMED.contains(text.trim()) ? null : tableGroup.get(text.trim());
                if (group != null) {
                    groups.add(group);
                }
                sql.append(text).append(' ');
            }
            Matcher ref = TABLE_REF.matcher(sql);
            while (ref.find()) {
                String group = tableGroup.get(ref.group(1));
                if (group != null) {
                    groups.add(group);
                }
            }
            Matcher word = Pattern.compile("\\b([A-Z]\\w*)\\b").matcher(c);
            while (word.find()) {
                if (declared.contains(word.group(1))) {
                    continue;
                }
                for (TypeRef type : types.getOrDefault(word.group(1), List.of())) {
                    if (type.visibleIn(e.getValue())) {
                        groups.add(type.group());
                    }
                }
            }
            for (String group : groups) {
                if (GROUPS.get(group).implementation().stream().noneMatch(path::startsWith)) {
                    actual.add(group + " " + path);
                }
            }
        }
        assertThat(actual).as("一个越界都没扫到 —— 扫描坏了？").isNotEmpty();

        Set<String> known = new TreeSet<>();
        for (String line : Files.readAllLines(KNOWN, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                known.add(line.trim());
            }
        }
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(known);
        Set<String> gone = new TreeSet<>(known);
        gone.removeAll(actual);
        assertThat(added).as("新的越界：这些代码绕过该组的唯一实现，直接访问基础能力库的表：\n  %s",
                String.join("\n  ", added)).isEmpty();
        assertThat(gone).as("这些已经不越界了，从 known-common-table-access.txt 删掉：\n  %s", String.join("\n  ", gone))
                .isEmpty();
    }

    private static String packageOf(String code) {
        Matcher m = PACKAGE.matcher(code);
        return m.find() ? m.group(1) : "";
    }

    private static List<Path> sources() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path base : List.of(BACKEND, BACKEND.resolveSibling("backend-plugins"))) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> top = Files.walk(base, 6)) {
                top.filter(p -> p.endsWith(Path.of("src", "main", "java"))).forEach(roots::add);
            }
        }
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (root.toString().contains("/target/")) {
                continue;
            }
            try (Stream<Path> all = Files.walk(root)) {
                all.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
            }
        }
        return files;
    }
}
