package ai.neargo.shop.elec.support;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 认表头：哪一列是料号、哪一列是数量……
 *
 * <p>各家 ERP 导出的表头五花八门（型号 / P/N / 料号 / Part No.）。认哪种写法是哪个字段，
 * <b>由调用方传进来</b>（{@code elc_header_alias}：全局种子 + 运营加的 + 这家学到的），这里只管
 * 「在哪一行、哪一列」。认不全时由 {@code ColumnResolver} 往下兜（大模型、手工）。
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

    /** 数量档表头：{@code 1} / {@code 1+} / {@code ≥100} / {@code 100-999} / {@code 1K起} */
    private static final java.util.regex.Pattern TIER_HEAD = java.util.regex.Pattern.compile(
            "^[≥>]?(\\d+(?:\\.\\d+)?)(K|千|W|万)?(?:[+起]|-\\d+(?:K|千|W|万)?|~\\d+(?:K|千|W|万)?|PCS)?$");

    private Columns() {
    }

    /**
     * @param headerRow 表头在第几行（从 0 起）；一个字段都认不出时为 -1。
     *                  <b>认出了但缺料号或数量时也给出</b>（取认出字段最多的那一行）—— 大模型与手工指定从这里接着补
     * @param map       字段 → 列序号
     * @param taxHint   价格列表头里写了「含税 / 未税」时的提示；没写为 null
     * @param conflicts 不止一列认成了同一个字段（取了第一列，但不可靠）
     */
    public record Guess(int headerRow, Map<Field, Integer> map, Boolean taxHint, List<PriceTierCol> tiers,
                        java.util.Set<Field> conflicts) {
        public boolean ok() {
            return headerRow >= 0 && map.containsKey(Field.MPN) && map.containsKey(Field.QTY);
        }
    }

    /** @param aliases 规范化写法（{@link HeaderNames#norm}）→ 字段 */
    public static Guess guess(List<List<String>> rows, Map<String, Field> aliases) {
        int best = -1;
        Map<Field, Integer> bestMap = Map.of();
        boolean bestComplete = false;
        for (int i = 0; i < Math.min(HEADER_SCAN_ROWS, rows.size()); i++) {
            Map<Field, Integer> m = mapRow(rows.get(i), aliases, null);
            boolean complete = m.containsKey(Field.MPN) && m.containsKey(Field.QTY);
            // 认全了料号与数量的行优先；同样认全（或同样没认全）时取认出字段多的
            if (m.isEmpty() || (bestComplete && !complete)) {
                continue;
            }
            if ((complete && !bestComplete) || m.size() > bestMap.size()) {
                best = i;
                bestMap = m;
                bestComplete = complete;
            }
        }
        return best < 0 ? new Guess(-1, Map.of(), null, List.of(), java.util.Set.of())
                : at(rows, best, aliases);
    }

    /** 表头行已知（记住的、大模型给的、重建时存下的）时，按这一行认 */
    public static Guess at(List<List<String>> rows, int headerRow, Map<String, Field> aliases) {
        if (headerRow < 0 || headerRow >= rows.size()) {
            return new Guess(-1, Map.of(), null, List.of(), java.util.Set.of());
        }
        java.util.Set<Field> conflicts = java.util.EnumSet.noneOf(Field.class);
        Map<Field, Integer> map = mapRow(rows.get(headerRow), aliases, conflicts);
        return new Guess(headerRow, map, taxHint(rows.get(headerRow), map), tierCols(rows.get(headerRow), map),
                java.util.Set.copyOf(conflicts));
    }

    /** 价格列表头里写了「含税 / 未税」 */
    public static Boolean taxHint(List<String> header, Map<Field, Integer> map) {
        Integer c = map.get(Field.PRICE);
        if (c == null || c >= header.size()) {
            return null;
        }
        String h = header.get(c);
        if (h.contains("未税") || h.contains("不含税")) {
            return false;
        }
        return h.contains("含税") ? true : null;
    }

    /**
     * 认出阶梯价那几列。**已经被别的字段占走的列不再当成阶梯**
     * （数量列的表头常常也是个数字，比如一张表第一行写着「1」）。
     */
    public static List<PriceTierCol> tierCols(List<String> header, Map<Field, Integer> taken) {
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

    private static Map<Field, Integer> mapRow(List<String> row, Map<String, Field> aliases,
                                              java.util.Set<Field> conflicts) {
        Map<Field, Integer> m = new EnumMap<>(Field.class);
        for (int c = 0; c < row.size(); c++) {
            String h = HeaderNames.norm(row.get(c));
            Field f = h.isEmpty() ? null : aliases.get(h);
            if (f == null) {
                continue;
            }
            // 同一字段只认第一次出现的那一列（有的表「型号」「型号备注」并存）；后面再撞上的记成冲突
            if (m.containsKey(f)) {
                if (conflicts != null) {
                    conflicts.add(f);
                }
            } else {
                m.put(f, c);
            }
        }
        return m;
    }
}
