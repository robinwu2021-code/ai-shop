package ai.neargo.shop.elec.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把采购敲进搜索框的一行字拆成「料号 + 厂牌 + 数量」。
 *
 * <p>真实的输入长这样：{@code TI TPS54331DR}、{@code stm32f103c8t6 2000}、
 * 从 Excel 粘过来的 {@code STM32F103C8T6\tST\t2000}。规则：
 * <ul>
 *   <li>按空白与中英文逗号、分号拆词（不按 / 拆：它是料号的一部分）</li>
 *   <li>没有数字、且认得出是厂牌别名的词 → 厂牌（{@code TI}、{@code 德州仪器}）</li>
 *   <li>纯数字（可带 k / 万）的词 → 数量，只取料号<b>之后</b>的第一个</li>
 *   <li>其余里规范化之后最长的那个 → 料号</li>
 * </ul>
 */
public final class Query {

    private static final Pattern SPLIT = Pattern.compile("[\\s,，;；]+");
    private static final Pattern NUMBER = Pattern.compile("^[0-9][0-9,.]*\\s*(K|k|千|W|w|万|M|m|PCS|pcs)?$");

    /**
     * @param mpnRaw  料号原文；null = 这一行没有像料号的词
     * @param mpnNorm 规范化之后
     * @param mfrCode 认出来的厂牌；null = 没写或认不出
     * @param mfrRaw  厂牌原文
     * @param qty     数量；null = 没写
     */
    public record Parsed(String mpnRaw, String mpnNorm, String mfrCode, String mfrRaw, Long qty) {
    }

    private Query() {
    }

    /** @param aliases 厂牌别名（规范化）→ mfr_code */
    public static Parsed parse(String raw, Map<String, String> aliases) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(null, "", null, null, null);
        }
        List<String> words = new ArrayList<>();
        for (String w : SPLIT.split(raw.trim())) {
            if (!w.isBlank()) {
                words.add(w.trim());
            }
        }
        String mfrCode = null;
        String mfrRaw = null;
        String mpnRaw = null;
        String mpnNorm = "";
        int mpnAt = -1;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            boolean hasDigit = w.chars().anyMatch(Character::isDigit);
            String alias = aliases.get(Mpn.mfrNorm(w));
            if (!hasDigit && alias != null && mfrCode == null) {
                mfrCode = alias;
                mfrRaw = w;
                continue;
            }
            if (NUMBER.matcher(w).matches()) {
                continue;
            }
            String n = Mpn.norm(w);
            if (n.length() > mpnNorm.length()) {
                mpnNorm = n;
                mpnRaw = w;
                mpnAt = i;
            }
        }
        Long qty = null;
        if (mpnAt >= 0) {
            for (int i = mpnAt + 1; i < words.size(); i++) {
                if (NUMBER.matcher(words.get(i)).matches()) {
                    qty = Cells.qty(words.get(i));
                    break;
                }
            }
        }
        return new Parsed(mpnRaw, mpnNorm, mfrCode, mfrRaw, qty);
    }
}
