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
        MPN, MFR, QTY, DC, PACKAGE, PRICE, MOQ;

        public boolean required() {
            return this == MPN || this == QTY;
        }
    }

    /** 只在前几行里找表头 —— 有的表第一行是公司抬头或「库存表 2026-09」 */
    private static final int HEADER_SCAN_ROWS = 10;

    private static final Map<Field, List<String>> NAMES = new EnumMap<>(Map.of(
            Field.MPN, List.of("型号", "料号", "物料型号", "产品型号", "规格型号", "型号规格", "物料编码", "器件型号",
                    "PARTNO", "PARTNUMBER", "PN", "MPN", "MODEL", "MFRPARTNO", "MFGPARTNO"),
            Field.MFR, List.of("品牌", "厂牌", "厂家", "厂商", "制造商", "生产商", "BRAND", "MFR", "MFG", "MANUFACTURER",
                    "MAKER"),
            Field.QTY, List.of("数量", "库存", "库存数量", "现货数量", "现货", "可售数量", "数目", "QTY", "QUANTITY",
                    "STOCK", "STOCKQTY", "AVAILABLE"),
            Field.DC, List.of("批号", "批次", "年份", "生产日期", "DC", "DATECODE", "D/C", "LOT"),
            Field.PACKAGE, List.of("封装", "封装规格", "PACKAGE", "PKG", "PACKAGING", "CASE"),
            Field.PRICE, List.of("单价", "价格", "报价", "含税单价", "未税单价", "不含税单价", "含税价", "未税价",
                    "PRICE", "UNITPRICE"),
            Field.MOQ, List.of("起订量", "最小起订量", "最小订购量", "MOQ", "MINQTY")));

    private Columns() {
    }

    /**
     * @param headerRow 表头在第几行（从 0 起）；找不到料号与数量两列时为 -1
     * @param map       字段 → 列序号
     * @param taxHint   价格列表头里写了「含税 / 未税」时的提示；没写为 null
     */
    public record Guess(int headerRow, Map<Field, Integer> map, Boolean taxHint) {
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
        if (best >= 0 && bestMap.containsKey(Field.PRICE)) {
            String h = rows.get(best).get(bestMap.get(Field.PRICE));
            if (h.contains("未税") || h.contains("不含税")) {
                tax = false;
            } else if (h.contains("含税")) {
                tax = true;
            }
        }
        return new Guess(best, bestMap, tax);
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
