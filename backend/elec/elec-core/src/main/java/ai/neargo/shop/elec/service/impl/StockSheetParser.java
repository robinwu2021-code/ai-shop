package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.dto.SupplierDtos.Issue;
import ai.neargo.shop.elec.support.Cells;
import ai.neargo.shop.elec.support.Columns;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.ElecValues;
import ai.neargo.shop.elec.support.Mpn;
import ai.neargo.shop.elec.support.SheetReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表 → 一行行解析结果 + 定位到格的问题。<b>不碰库</b>：与现有库存比对（新增 / 更新 / 下架）是调用方的事。
 *
 * <p><b>一行有几处错就记几处</b>：料号空、数量也读不出，两处都报 —— 只报第一处的话，他改完料号回传，
 * 才发现数量也不对，要来回两趟。警告只记在没有错误的行上：错误行本来就不上架，再叠警告是噪声。
 */
final class StockSheetParser {

    static final String ERROR = "ERROR";
    static final String WARN = "WARN";

    private static final int VALUE_MAX = 64;

    private StockSheetParser() {
    }

    /** 一行解析的结果。只有 valid 的行会上架 */
    record Parsed(int row, String mpnRaw, String mpnNorm, String mfrRaw, String lineKey, long qty,
                  String dateCode, Integer dcYear, String pkg, Integer moq, Integer spq,
                  List<long[]> tiers, Long priceE6, String currency, String packing, String cond,
                  Integer leadDays, String region) {
    }

    /**
     * @param parsed 能上架的行解析结果；有错误时为 null
     * @param cells  有问题（含警告）的行才留原样单元格 —— 导出问题行用，其余行不留，省内存
     */
    record Row(int row, Parsed parsed, String mpnRaw, String mfrRaw, String qtyRaw, List<Issue> issues,
               List<String> cells) {
        boolean hasError() {
            return parsed == null;
        }
    }

    record Result(List<Row> rows, int total, int invalid, int warn, Map<String, Integer> issueCounts) {
    }

    /**
     * @param rows        整张表（含表头以上的行）
     * @param headerRow   表头在第几行
     * @param map         字段 → 列序号。<b>必须含料号与数量</b>
     * @param tierCols    阶梯价列
     * @param mfrAliases  厂牌写法（{@link Mpn#mfrNorm}）→ 厂牌码。判「厂牌认不出」用
     */
    static Result parse(List<List<String>> rows, int headerRow, Map<Field, Integer> map,
                        List<Columns.PriceTierCol> tierCols, Map<String, String> mfrAliases) {
        List<String> header = headerRow >= 0 && headerRow < rows.size() ? rows.get(headerRow) : List.of();
        List<Row> out = new ArrayList<>();
        Map<String, Integer> firstRowOfKey = new HashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        int invalid = 0;
        int warn = 0;
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            if (SheetReader.blank(cells)) {
                continue;
            }
            total++;
            int rowNo = i + 1;
            List<Issue> issues = new ArrayList<>();
            Integer mpnCol = map.get(Field.MPN);
            Integer qtyCol = map.get(Field.QTY);
            String mpnRaw = Cells.text(cell(cells, mpnCol), 64);
            String qtyRaw = cell(cells, qtyCol);
            String mfrRaw = Cells.text(cell(cells, map.get(Field.MFR)), 64);

            String norm = mpnRaw == null ? "" : Mpn.norm(mpnRaw);
            if (mpnRaw == null) {
                issues.add(issue(rowNo, mpnCol, header, "", "MPN_MISSING", ERROR));
            } else if (!Mpn.looksLikeMpn(norm)) {
                issues.add(issue(rowNo, mpnCol, header, mpnRaw, "MPN_INVALID", ERROR));
            }
            Long qty = Cells.qty(qtyRaw);
            if (qty == null) {
                // 数量为 0 不上架（0 不是有货），但要说清是「0」而不是「读不出」—— 他要改的地方不一样
                issues.add(issue(rowNo, qtyCol, header, qtyRaw == null ? "" : qtyRaw,
                        isZero(qtyRaw) ? "QTY_ZERO" : "QTY_INVALID", ERROR));
            }
            String dc = Cells.text(cell(cells, map.get(Field.DC)), 16);
            String key = null;
            if (issues.isEmpty()) {
                key = lineKey(norm, mfrRaw, dc);
                Integer first = firstRowOfKey.putIfAbsent(key, rowNo);
                if (first != null) {
                    issues.add(new Issue(rowNo, -1, null, String.valueOf(first), "DUPLICATE", ERROR));
                }
            }
            if (!issues.isEmpty()) {
                invalid++;
                count(counts, issues);
                out.add(new Row(rowNo, null, mpnRaw, mfrRaw, qtyRaw, List.copyOf(issues), List.copyOf(cells)));
                continue;
            }

            // ── 警告：照常上架 ──
            Integer mfrCol = map.get(Field.MFR);
            // 厂牌列根本没认出时不逐行报（那会让每一行都带警告）：列映射页上「厂牌」空着已经说明了
            if (mfrCol != null && mfrRaw == null) {
                issues.add(issue(rowNo, mfrCol, header, "", "MFR_MISSING", WARN));
            } else if (mfrRaw != null && !mfrAliases.containsKey(Mpn.mfrNorm(mfrRaw))) {
                issues.add(issue(rowNo, mfrCol, header, mfrRaw, "MFR_UNKNOWN", WARN));
            }
            Integer dcYear = Cells.dcYear(dc);
            if (dc != null && dcYear == null) {
                issues.add(issue(rowNo, map.get(Field.DC), header, dc, "DC_UNPARSED", WARN));
            }

            /*
             * 阶梯价：先收表头是数量档的那几列（「1-99」「100+」「1000」），
             * 再把「单价」那一列当成 minQty = 起订量（没写起订量就是 1）的一档。
             * 两者都没有就是没报价 —— **空 ≠ 0**，没报价的行照样上架，买家看到的是「暂无报价」。
             */
            Integer moq = Cells.moq(cell(cells, map.get(Field.MOQ)));
            List<long[]> rawTiers = new ArrayList<>();
            for (Columns.PriceTierCol tc : tierCols) {
                Long e6 = Cells.priceE6(cell(cells, tc.col()));
                if (e6 != null) {
                    rawTiers.add(new long[]{tc.minQty(), e6});
                }
            }
            Long single = Cells.priceE6(cell(cells, map.get(Field.PRICE)));
            if (single != null) {
                rawTiers.add(new long[]{moq == null ? 1L : moq, single});
            }
            List<long[]> tiers = Cells.tiers(rawTiers);
            Parsed p = new Parsed(rowNo, mpnRaw, norm, mfrRaw, key, qty, dc, dcYear,
                    Cells.text(cell(cells, map.get(Field.PACKAGE)), 32), moq,
                    Cells.moq(cell(cells, map.get(Field.SPQ))),
                    tiers, tiers.isEmpty() ? null : tiers.get(0)[1],
                    ElecValues.currencyOf(cell(cells, map.get(Field.CURRENCY))),
                    ElecValues.packingOf(cell(cells, map.get(Field.PACKING))),
                    ElecValues.condOf(cell(cells, map.get(Field.CONDITION))),
                    ElecValues.leadDaysOf(cell(cells, map.get(Field.LEAD))),
                    Cells.text(cell(cells, map.get(Field.REGION)), 32));
            if (!issues.isEmpty()) {
                warn++;
                count(counts, issues);
            }
            out.add(new Row(rowNo, p, mpnRaw, mfrRaw, qtyRaw, List.copyOf(issues),
                    issues.isEmpty() ? null : List.copyOf(cells)));
        }
        return new Result(out, total, invalid, warn, counts);
    }

    /**
     * 认行的键：规范化料号 | 规范化厂牌 | 批号。同一料号不同批次是两行库存（价格、年份都可能不同），
     * 所以批号在键里；厂牌用原文规范化而不是解析后的厂牌码 —— 别名表一改，认行的结果不能跟着变。
     */
    static String lineKey(String mpnNorm, String mfrRaw, String dc) {
        String k = mpnNorm + "|" + Mpn.mfrNorm(mfrRaw) + "|" + (dc == null ? "" : dc.trim().toUpperCase(Locale.ROOT));
        return k.length() > 160 ? k.substring(0, 160) : k;
    }

    /** 「0」「0.0」「0 pcs」「0个」：写了，但是 0 */
    static boolean isZero(String raw) {
        if (raw == null) {
            return false;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        String rest = raw.replaceAll("(?i)[0-9.\\s,，]|PCS|PC|个|片|只", "");
        return !digits.isEmpty() && digits.matches("0+(\\.0*)?") && rest.isEmpty();
    }

    static String cell(List<String> cells, Integer col) {
        return col == null || col < 0 || col >= cells.size() ? null : cells.get(col);
    }

    private static Issue issue(int row, Integer col, List<String> header, String value, String code, String level) {
        int c = col == null ? -1 : col;
        String h = c >= 0 && c < header.size() ? header.get(c) : null;
        String v = value == null ? "" : value.strip();
        return new Issue(row, c, h, v.length() > VALUE_MAX ? v.substring(0, VALUE_MAX) : v, code, level);
    }

    private static void count(Map<String, Integer> counts, List<Issue> issues) {
        for (Issue x : issues) {
            counts.merge(x.code(), 1, Integer::sum);
        }
    }
}
