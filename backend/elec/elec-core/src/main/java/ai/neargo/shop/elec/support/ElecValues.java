package ai.neargo.shop.elec.support;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本域那几个受限取值域，连同它们在供应商表格里的各种写法。
 *
 * <p><b>为什么要「写法 → 码」这张表</b>：与厂牌别名同一个道理 —— 供应商表格里写的是
 * 「原装原包」「全新原装」「原厂原包」「New Original」，都是同一件事。
 * 认不出就只能当成空，而货况是元器件最要命的维度，当成空等于把它丢了。
 */
public final class ElecValues {

    // ── 货况。价差好几倍，认错比认不出更糟 ──
    public static final String COND_ORIGINAL = "ORIGINAL";
    public static final String COND_LOOSE = "LOOSE";
    public static final String COND_PULLED = "PULLED";
    public static final String COND_REFURB = "REFURB";
    public static final Set<String> CONDITIONS = Set.of(COND_ORIGINAL, COND_LOOSE, COND_PULLED, COND_REFURB);

    // ── 包装 ──
    public static final String PACK_REEL = "REEL";
    public static final String PACK_TRAY = "TRAY";
    public static final String PACK_TUBE = "TUBE";
    public static final String PACK_CUT_TAPE = "CUT_TAPE";
    public static final String PACK_BULK = "BULK";
    public static final String PACK_BOX = "BOX";
    public static final Set<String> PACKINGS =
            Set.of(PACK_REEL, PACK_TRAY, PACK_TUBE, PACK_CUT_TAPE, PACK_BULK, PACK_BOX);

    /** 买家的要求：多一个 ANY（不限），少一个 REFURB（没人会指定要翻新件） */
    public static final Set<String> COND_REQS = Set.of("ANY", COND_ORIGINAL, "NEW");
    public static final Set<String> PACKING_REQS = Set.of("ANY", PACK_REEL, PACK_CUT_TAPE);

    public static final Set<String> CURRENCIES = Set.of("CNY", "USD", "HKD");

    /** 写法 → 货况码。键是规范化之后的（大写、去空白与标点） */
    private static final Map<String, String> COND_ALIAS = Map.ofEntries(
            Map.entry("原装原包", COND_ORIGINAL), Map.entry("原厂原包", COND_ORIGINAL),
            Map.entry("全新原装", COND_ORIGINAL), Map.entry("原装全新", COND_ORIGINAL),
            Map.entry("原包", COND_ORIGINAL), Map.entry("原装", COND_ORIGINAL),
            Map.entry("NEWORIGINAL", COND_ORIGINAL), Map.entry("ORIGINAL", COND_ORIGINAL),
            Map.entry("BRANDNEW", COND_ORIGINAL),
            Map.entry("原装散新", COND_LOOSE), Map.entry("散新", COND_LOOSE),
            Map.entry("原装散装", COND_LOOSE), Map.entry("散装", COND_LOOSE),
            Map.entry("LOOSE", COND_LOOSE),
            Map.entry("拆机", COND_PULLED), Map.entry("拆机件", COND_PULLED),
            Map.entry("PULLED", COND_PULLED), Map.entry("USED", COND_PULLED),
            Map.entry("翻新", COND_REFURB), Map.entry("REFURB", COND_REFURB),
            Map.entry("REFURBISHED", COND_REFURB));

    /** 写法 → 包装码 */
    private static final Map<String, String> PACK_ALIAS = Map.ofEntries(
            Map.entry("整盘", PACK_REEL), Map.entry("盘装", PACK_REEL), Map.entry("卷带", PACK_REEL),
            Map.entry("编带", PACK_REEL), Map.entry("REEL", PACK_REEL), Map.entry("TR", PACK_REEL),
            Map.entry("托盘", PACK_TRAY), Map.entry("盘", PACK_TRAY), Map.entry("TRAY", PACK_TRAY),
            Map.entry("管装", PACK_TUBE), Map.entry("管", PACK_TUBE), Map.entry("TUBE", PACK_TUBE),
            Map.entry("剪带", PACK_CUT_TAPE), Map.entry("剪切带", PACK_CUT_TAPE),
            Map.entry("CUTTAPE", PACK_CUT_TAPE), Map.entry("CT", PACK_CUT_TAPE),
            Map.entry("散装", PACK_BULK), Map.entry("BULK", PACK_BULK),
            Map.entry("盒装", PACK_BOX), Map.entry("BOX", PACK_BOX));

    /** 写法 → 币种 */
    private static final Map<String, String> CURRENCY_ALIAS = Map.ofEntries(
            Map.entry("RMB", "CNY"), Map.entry("CNY", "CNY"), Map.entry("人民币", "CNY"), Map.entry("元", "CNY"),
            Map.entry("USD", "USD"), Map.entry("美元", "USD"), Map.entry("美金", "USD"),
            Map.entry("HKD", "HKD"), Map.entry("港币", "HKD"), Map.entry("港元", "HKD"));

    private ElecValues() {
    }

    /** @return 认不出返回 null（当成没写）。**不猜**：货况猜错了是质量事故 */
    public static String condOf(String raw) {
        return lookup(COND_ALIAS, raw);
    }

    public static String packingOf(String raw) {
        return lookup(PACK_ALIAS, raw);
    }

    /** @return 认不出返回 null；调用方按「表上说的币种」或默认 CNY 处理 */
    public static String currencyOf(String raw) {
        return lookup(CURRENCY_ALIAS, raw);
    }

    /**
     * 交期：{@code 现货} / {@code 0} → 0；{@code 7天} / {@code 7} → 7；{@code 2周} → 14。
     *
     * @return 认不出为 null（空 ≠ 现货 —— 把「没说」当成现货，买家会按现货下单）
     */
    public static Integer leadDaysOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = Mpn.mfrNorm(raw);
        if (s.contains("现货") || s.equals("STOCK") || s.equals("INSTOCK") || s.equals("0")) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,3})(天|日|D|DAY|DAYS|周|WEEK|WEEKS)?$")
                .matcher(s);
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2);
        int days = unit.equals("周") || unit.startsWith("WEEK") ? n * 7 : n;
        return days >= 0 && days <= 365 ? days : null;
    }

    /** 一格里写两样时的分隔符：「原装原包/编带」「原装 编带」「原装、编带」 */
    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile("[/／\\s,，、|+]+");

    /**
     * 按分隔符切开、逐段<b>精确</b>查。
     *
     * <p><b>刻意不做包含匹配</b>：「非原装」包含「原装」，包含匹配会把它认成 ORIGINAL ——
     * 而货况认错是质量事故。认不出就返回 null（当成没写），由人去看那一行。
     * 这条是消融时发现的：包含匹配当时没有任何用例覆盖，而它一直在跑。
     */
    private static String lookup(Map<String, String> alias, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String hit = alias.get(Mpn.mfrNorm(raw));
        if (hit != null) {
            return hit;
        }
        for (String part : SPLIT.split(raw.trim())) {
            String v = alias.get(Mpn.mfrNorm(part));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 大写。CHAR(3) 列，认不出时调用方给默认值 */
    public static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
