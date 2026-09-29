package ai.neargo.shop.elec.support;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 认表头：哪一列是料号、哪一列是数量……
 *
 * <p>各家 ERP 导出的表头五花八门（型号 / P/N / 料号 / Part No.），这里收的是常见写法。
 * 认错了供应商在预览页改一下就行（改过的映射按供应商记住，下次上传默认沿用）。
 */
public final class Columns {

    /** 可以映射的字段。料号与数量必须有，其余可空 */
    public enum Field {
        MPN, MFR, QTY, DC, PACKAGE, PRICE, MOQ,
        /** 最小包装量 */
        SPQ,
        /** 整盘 / 托盘 / 管装 / 剪带 */
        PACKING,
        /** 原装原包 / 散新 / 拆机 —— 元器件最要命的那一列 */
        CONDITION,
        /** 币种。认不出时按整张表的默认（供应商在上传页选） */
        CURRENCY,
        /** 交期 */
        LEAD,
        /** 货在哪 */
        REGION;

        public boolean required() {
            return this == MPN || this == QTY;
        }
    }

    /**
     * 阶梯价列：表头本身就是数量档（{@code 1-99} / {@code 100+} / {@code ≥1000} / {@code 1K}）。
     *
     * <p><b>这类列认不出来的后果不是少一个字段，是整张阶梯价表丢掉</b> ——
     * 而阶梯价正是元器件报价的形状。所以它不进 {@link Field}（那是一列一个字段），
     * 单独一张「列序号 → 这一档从多少起」。
     */
    public record PriceTierCol(int col, long minQty) {
    }

    /** 只在前几行里找表头 —— 有的表第一行是公司抬头或「库存表 2026-09」 */
    private static final int HEADER_SCAN_ROWS = 10;

    private static final Map<Field, List<String>> NAMES = new EnumMap<>(Map.ofEntries(
            Map.entry(Field.MPN, List.of("型号", "料号", "物料型号", "产品型号", "规格型号", "型号规格", "物料编码", "器件型号",
                    "PARTNO", "PARTNUMBER", "PN", "MPN", "MODEL", "MFRPARTNO", "MFGPARTNO")),
            Map.entry(Field.MFR, List.of("品牌", "厂牌", "厂家", "厂商", "制造商", "生产商", "BRAND", "MFR", "MFG", "MANUFACTURER",
                    "MAKER")),
            Map.entry(Field.QTY, List.of("数量", "库存", "库存数量", "现货数量", "现货", "可售数量", "数目", "QTY", "QUANTITY",
                    "STOCK", "STOCKQTY", "AVAILABLE")),
            Map.entry(Field.DC, List.of("批号", "批次", "年份", "生产日期", "DC", "DATECODE", "D/C", "LOT")),
            Map.entry(Field.PACKAGE, List.of("封装", "封装规格", "PACKAGE", "PKG", "PACKAGING", "CASE")),
            Map.entry(Field.PRICE, List.of("单价", "价格", "报价", "含税单价", "未税单价", "不含税单价", "含税价", "未税价",
                    "PRICE", "UNITPRICE")),
            Map.entry(Field.MOQ, List.of("起订量", "最小起订量", "最小订购量", "MOQ", "MINQTY")),
            Map.entry(Field.SPQ, List.of("最小包装量", "包装量", "标准包装", "整包数量", "SPQ", "MPQ", "PACKQTY")),
            Map.entry(Field.PACKING, List.of("包装", "包装方式", "包装形式", "PACKING", "PACKAGING", "PACKTYPE")),
            Map.entry(Field.CONDITION, List.of("品质", "货况", "品相", "新旧", "质量", "CONDITION", "QUALITY", "GRADE")),
            Map.entry(Field.CURRENCY, List.of("币种", "货币", "CURRENCY", "CCY")),
            Map.entry(Field.LEAD, List.of("交期", "货期", "交货期", "供货周期", "LEADTIME", "LEAD", "DELIVERY")),
            Map.entry(Field.REGION, List.of("货源地", "所在地", "发货地", "仓库", "货位", "LOCATION", "REGION", "WAREHOUSE"))));

    /** 数量档表头：{@code 1} / {@code 1+} / {@code ≥100} / {@code 100-999} / {@code 1K起} */
    private static final java.util.regex.Pattern TIER_HEAD = java.util.regex.Pattern.compile(
            "^[≥>]?(\\d+(?:\\.\\d+)?)(K|千|W|万)?(?:[+起]|-\\d+(?:K|千|W|万)?|~\\d+(?:K|千|W|万)?|PCS)?$");

    private Columns() {
    }

    /**
     * @param headerRow 表头在第几行（从 0 起）；找不到料号与数量两列时为 -1
     * @param map       字段 → 列序号
     * @param taxHint   价格列表头里写了「含税 / 未税」时的提示；没写为 null
     */
    public record Guess(int headerRow, Map<Field, Integer> map, Boolean taxHint, List<PriceTierCol> tiers) {
        public boolean ok() {
            return headerRow >= 0 && map.containsKey(Field.MPN) && map.containsKey(Field.QTY);
        }
    }

    public static Guess guess(List<List<String>> rows) {
        int best = -1;
        Map<Field, Integer> bestMap = Map.of();
        for (int i = 0; i < Math.min(HEADER_SCAN_ROWS, rows.size()); i++) {
            Map<Field, Integer> m = mapRow(rows.get(i));
            if (m.containsKey(Field.MPN) && m.containsKey(Field.QTY) && m.size() > bestMap.size()) {
                best = i;
                bestMap = m;
            }
        }
        Boolean tax = null;
        List<PriceTierCol> tiers = List.of();
        if (best >= 0) {
            if (bestMap.containsKey(Field.PRICE)) {
                String h = rows.get(best).get(bestMap.get(Field.PRICE));
                if (h.contains("未税") || h.contains("不含税")) {
                    tax = false;
                } else if (h.contains("含税")) {
                    tax = true;
                }
            }
            tiers = tierCols(rows.get(best), bestMap);
        }
        return new Guess(best, bestMap, tax, tiers);
    }

    /**
     * 认出阶梯价那几列。**已经被别的字段占走的列不再当成阶梯**
     * （数量列的表头常常也是个数字，比如一张表第一行写着「1」）。
     */
    private static List<PriceTierCol> tierCols(List<String> header, Map<Field, Integer> taken) {
        java.util.Set<Integer> used = new java.util.HashSet<>(taken.values());
        List<PriceTierCol> out = new java.util.ArrayList<>();
        for (int c = 0; c < header.size(); c++) {
            if (used.contains(c)) {
                continue;
            }
            Long q = tierQty(header.get(c));
            if (q != null) {
                out.add(new PriceTierCol(c, q));
            }
        }
        // 一列不成阶梯：单独一个「100+」多半是「100 起订」之类的说明，不是价格列
        if (out.size() < 2) {
            return List.of();
        }
        out.sort(java.util.Comparator.comparingLong(PriceTierCol::minQty));
        return List.copyOf(out);
    }

    /** @return 这一档从多少起；不是数量档表头为 null */
    static Long tierQty(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replace(",", "").replace("，", "")
                .replace(" ", "").replace("片", "").replace("个", "");
        java.util.regex.Matcher m = TIER_HEAD.matcher(s);
        if (!m.matches()) {
            return null;
        }
        java.math.BigDecimal n = new java.math.BigDecimal(m.group(1));
        String unit = m.group(2);
        if (unit != null) {
            n = n.multiply(java.math.BigDecimal.valueOf("K".equals(unit) || "千".equals(unit) ? 1_000 : 10_000));
        }
        long v = n.longValue();
        return v > 0 && v <= 10_000_000 ? v : null;
    }

    private static Map<Field, Integer> mapRow(List<String> row) {
        Map<Field, Integer> m = new EnumMap<>(Field.class);
        for (int c = 0; c < row.size(); c++) {
            String h = key(row.get(c));
            if (h.isEmpty()) {
                continue;
            }
            for (Map.Entry<Field, List<String>> e : NAMES.entrySet()) {
                // 同一字段只认第一次出现的那一列（有的表「型号」「型号备注」并存）
                if (!m.containsKey(e.getKey()) && e.getValue().stream().anyMatch(n -> key(n).equals(h))) {
                    m.put(e.getKey(), c);
                }
            }
        }
        return m;
    }

    /** 比较用的表头：大写、去空白与标点（保留 /，因为 D/C 与 P/N 就是这么写的） */
    private static String key(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int cp : raw.toUpperCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(cp) || cp == '/') {
                sb.appendCodePoint(cp);
            }
        }
        String s = sb.toString();
        // 「P/N」与「PN」「D/C」与「DC」都认
        return s.equals("P/N") ? "PN" : s;
    }
}
