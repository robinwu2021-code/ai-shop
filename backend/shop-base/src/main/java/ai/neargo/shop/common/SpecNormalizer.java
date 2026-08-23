package ai.neargo.shop.common;

import java.util.Map;

/**
 * 规格文案的规范化。<b>入库前必过这一道。</b>
 *
 * <p>同一个规格，三家店会写成「500g」「500 G」「５００ｇ」「五百克」——
 * 归一库的全部价值建立在「同一件事只有一条记录」上，而没有这一步，
 * 值池会在三个月内长出一堆看着不同、其实一样的行，且每一条都合法、都不报错。
 *
 * <p><b>端上也有一份同样的实现</b>（{@code packages/shared/src/utils/spec-normalize.ts}）：
 * 只在一侧做，另一侧就是漏网的入口 —— 商家在 b-app 输入的、运营在 ops-web 输入的，
 * 走的是两条路。两份实现由 {@code spec-normalize.test.ts} 用同一张用例表钉住。
 *
 * <p>这一步<b>只规范形式，不改语义</b>：「1斤」不会被改写成「500g」——
 * 那是别名与归一量该管的事（{@code prd_spec_value.aliases / numeric_value}），
 * 在这里做会把商家想说的话改掉。
 */
public final class SpecNormalizer {

    private SpecNormalizer() {
    }

    /** 中文数字 → 阿拉伯。只覆盖规格里真会出现的那几个 */
    private static final Map<Character, Character> CN_DIGITS = Map.of(
            '〇', '0', '零', '0', '一', '1', '二', '2', '三', '3',
            '四', '4', '五', '5', '六', '6', '七', '7');

    private static final Map<Character, Character> CN_DIGITS_2 = Map.of(
            '八', '8', '九', '9');

    /**
     * 规范化一个规格文案（维度名、值文案、别名都走它）。
     *
     * <ol>
     *   <li>去首尾空白，中间连续空白压成一个</li>
     *   <li>全角字符转半角：{@code ５００ｇ → 500g}</li>
     *   <li>单位统一小写：{@code 500G → 500g}、{@code 1KG → 1kg}、{@code 1L} 保持大写 L
     *       （升的国际符号就是大写，写成 {@code 1l} 会与数字 1 混）</li>
     *   <li>数字与单位之间不留空格：{@code 500 g → 500g}</li>
     *   <li>中文数字转阿拉伯：{@code 五斤 → 5斤}</li>
     * </ol>
     *
     * @return {@code null} 进 {@code null} 出 —— 调用方常常在判空之前就想先规范一下
     */
    public static String label(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            // 全角 ！-～ → 半角；全角空格单独一条（它不在那段连续区里）
            if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                sb.append(' ');
            } else {
                Character d = CN_DIGITS.get(c);
                if (d == null) {
                    d = CN_DIGITS_2.get(c);
                }
                sb.append(d == null ? c : d);
            }
        }
        s = sb.toString().replaceAll("\\s+", " ").trim();
        // 数字与单位之间的空格：500 g → 500g。只在后面确实是单位时才吃掉这个空格
        s = s.replaceAll("(?<=\\d) +(?=(?i:g|kg|ml|l|cm|mm|m|寸|斤|克|千克|升|毫升|厘米|米|支|件|盒|袋|人|分钟|小时)\\b|$)", "");
        return unifyUnit(s);
    }

    /**
     * 单位大小写统一。**升是唯一的例外**：国际符号就是大写 {@code L}，
     * 小写 {@code l} 在多数字体里与数字 1 分不开，而规格是要被人一眼读准的东西。
     */
    private static String unifyUnit(String s) {
        return s
                .replaceAll("(?<=\\d)\\s*(?i:kg)\\b", "kg")
                .replaceAll("(?<=\\d)\\s*(?i:g)\\b", "g")
                .replaceAll("(?<=\\d)\\s*(?i:ml)\\b", "ml")
                .replaceAll("(?<=\\d)\\s*(?i:l)\\b", "L")
                .replaceAll("(?<=\\d)\\s*(?i:cm)\\b", "cm")
                .replaceAll("(?<=\\d)\\s*(?i:mm)\\b", "mm")
                .replaceAll("(?<=\\d)\\s*(?i:w)\\b", "W");
    }
}
