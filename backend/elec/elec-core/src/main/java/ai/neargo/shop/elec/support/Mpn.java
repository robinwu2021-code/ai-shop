package ai.neargo.shop.elec.support;

import java.util.List;
import java.util.Locale;

/**
 * 料号与厂牌的规范化。所有匹配与检索都走规范化之后的值，库里只存一份规则的结果 ——
 * <b>规则变了要全量重算</b>，所以改这里之前先想清楚存量怎么办。
 */
public final class Mpn {

    /** 厂牌写法里常见的公司后缀，去掉之后才比较（Texas Instruments Inc. = Texas Instruments） */
    private static final List<String> MFR_SUFFIXES = List.of(
            "股份有限公司", "有限责任公司", "有限公司", "公司", "集团",
            "CORPORATION", "INCORPORATED", "LIMITED", "COMPANY", "GMBH", "CORP", "INC", "LTD", "CO", "AG", "NV", "BV");

    private Mpn() {
    }

    /**
     * 料号：大写，只留字母数字与 {@code / . # + ,}。
     *
     * <p>去掉空白、横杠、下划线：{@code stm32f103-c8t6} 与 {@code STM32F103C8T6} 是同一个料号。
     * 保留 {@code / . # + ,}：{@code 1.5KE6.8CA}、{@code 74HC595D/T3} 里它们有意义。
     * 其余一律丢掉 —— 这也保证了拿它拼 LIKE 时不会混进 {@code %} 与 {@code _}。
     *
     * @return 规范化之后为空时返回空串（调用方据此判「不是料号」）
     */
    public static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '#'
                    || c == '+' || c == ',') {
                sb.append(c);
            }
        }
        return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    }

    /**
     * 像不像一个料号：规范化之后至少 3 个字符、且至少有一个数字。
     * 「0805 贴片电阻」规范化后是 {@code 0805}，有数字但只有 4 位 —— 放行；
     * 真正挡掉的是「电阻」「备注」这类纯文字行。
     */
    public static boolean looksLikeMpn(String norm) {
        if (norm == null || norm.length() < 3) {
            return false;
        }
        for (char c : norm.toCharArray()) {
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /** 分段键的最短长度。单个字符（「6」「T」）搜出来没有意义 */
    public static final int MIN_KEY = 2;

    /**
     * 分段键：在「字母 ↔ 数字」交界处（以及 / . # + , 之后）切开，每个切点起的后缀一个键。
     *
     * <pre>
     *   STM32F103C8T6 → [STM32F103C8T6@0, 32F103C8T6@3, F103C8T6@5, 103C8T6@6, C8T6@9, 8T6@10, T6@11]
     * </pre>
     *
     * <p>采购记得的往往是中间一截（F103C8、C8T6），而库里只能对 mpn_norm 做前缀匹配。
     * 有了这些键，「中段匹配」就变成对键的前缀匹配，照样走索引。
     * 只在交界处切：每个料号 6～10 个键，不是逐字符切的 60 个。
     *
     * @return 键与它的起点（0 = 整个料号）
     */
    public static java.util.List<java.util.Map.Entry<String, Integer>> segmentKeys(String norm) {
        java.util.List<java.util.Map.Entry<String, Integer>> out = new java.util.ArrayList<>();
        if (norm == null || norm.isEmpty()) {
            return out;
        }
        out.add(java.util.Map.entry(norm, 0));
        for (int i = 1; i <= norm.length() - MIN_KEY; i++) {
            char a = norm.charAt(i - 1);
            char b = norm.charAt(i);
            boolean cut = Character.isDigit(a) != Character.isDigit(b) && Character.isLetterOrDigit(a)
                    && Character.isLetterOrDigit(b);
            boolean afterSep = !Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b);
            if (cut || afterSep) {
                out.add(java.util.Map.entry(norm.substring(i), i));
            }
        }
        return out;
    }

    /** 厂牌：大写，去空白与标点，保留中日韩文字，去掉公司后缀。 */
    public static String mfrNorm(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int cp : raw.toUpperCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        String s = sb.toString();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : MFR_SUFFIXES) {
                if (s.length() > suffix.length() && s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return s.length() > 128 ? s.substring(0, 128) : s;
    }
}
