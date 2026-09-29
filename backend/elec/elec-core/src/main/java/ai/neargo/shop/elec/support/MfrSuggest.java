package ai.neargo.shop.elec.support;

import java.util.Map;

/**
 * 认不出的厂牌写法 → 建议它是哪家。<b>只是建议</b>：运营点了才写进别名表。
 *
 * <p>规则：在别名表里找与这个写法<b>互为前缀</b>的别名，取最长的那条。
 * 「TEXASINSTRUMENT」← 别名「TEXASINSTRUMENTS」；「STMICROELEC」← 别名「STMICRO」。
 *
 * <p><b>较短一方要够长</b>（拉丁至少 4 个字符，含中文至少 2 个）：
 * 别名表里有「ST」「TI」「LT」「IR」这类两个字母的缩写，不设下限的话
 * 「STARCHIP」会被建议成意法、「TIANMA」会被建议成德州仪器 —— 而运营看到建议多半会直接点。
 * 宁可不给建议，也不给一个看起来有把握的错建议。
 */
public final class MfrSuggest {

    static final int MIN_LATIN = 4;
    static final int MIN_CJK = 2;

    private MfrSuggest() {
    }

    /**
     * @param norm    规范化后的写法（{@link Mpn#mfrNorm}）
     * @param aliases 别名表：alias_norm → mfr_code
     * @return 建议的 mfr_code；没把握为 null
     */
    public static String suggest(String norm, Map<String, String> aliases) {
        if (norm == null || norm.isEmpty()) {
            return null;
        }
        String exact = aliases.get(norm);
        if (exact != null) {
            return exact;
        }
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            String a = e.getKey();
            if (!(norm.startsWith(a) || a.startsWith(norm))) {
                continue;
            }
            String shorter = a.length() <= norm.length() ? a : norm;
            if (shorter.length() < minLen(shorter)) {
                continue;
            }
            if (a.length() > bestLen) {
                best = e.getValue();
                bestLen = a.length();
            }
        }
        return best;
    }

    private static int minLen(String s) {
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                ? MIN_CJK : MIN_LATIN;
    }
}
