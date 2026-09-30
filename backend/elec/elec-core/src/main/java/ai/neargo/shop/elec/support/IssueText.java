package ai.neargo.shop.elec.support;

import java.util.Map;

/**
 * 问题码 → 人话，导出问题行的「问题」列用：{@code D12（数量）读不出：约2千}。
 *
 * <p>只有中文：导出给的是供应商，而元器件小程序只有中文。端上页面里的人话在 elec-app 的 format.ts，
 * 两边的码是同一份（{@code IssueTextTest} 钉住码的全集）。
 */
public final class IssueText {

    public static final Map<String, String> TEXT = Map.of(
            "MPN_MISSING", "没有料号",
            "MPN_INVALID", "不像料号",
            "QTY_INVALID", "数量读不出",
            "DUPLICATE", "与第 %s 行重复",
            "MFR_MISSING", "没写厂牌",
            "MFR_UNKNOWN", "厂牌认不出",
            "QTY_ZERO", "数量为 0",
            "DC_UNPARSED", "批号读不出年份");

    private IssueText() {
    }

    /** @param col -1 = 整行的问题 */
    public static String of(int row, int col, String header, String value, String code) {
        String what = TEXT.getOrDefault(code, code);
        if ("DUPLICATE".equals(code)) {
            return "第 " + row + " 行" + what.formatted(value);
        }
        String where = col < 0 ? "第 " + row + " 行"
                : SheetWriter.colName(col) + row + (header == null || header.isBlank() ? "" : "（" + header.strip() + "）");
        // 数量为 0：原值就是 0，再带一遍是废话
        boolean noValue = value == null || value.isBlank() || "QTY_ZERO".equals(code);
        return noValue ? where + what : where + what + "：" + value;
    }
}
