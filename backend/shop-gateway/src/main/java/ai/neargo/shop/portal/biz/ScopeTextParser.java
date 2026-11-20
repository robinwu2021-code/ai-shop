package ai.neargo.shop.portal.biz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 经营范围文字录入 · 拆句判向（TDD-经营范围文字录入 §2「识别规则」）。<b>纯函数</b>，不碰库。
 *
 * <p>只做两件事：把一句话拆成一个个地名短语、判出每个短语是纳入还是排除，以及有没有「全国 / 不限」。
 * 地名认成区划码是 {@link ScopeTextResolver} 的事 —— 码一律来自库，这里一个码都不产出。
 *
 * <p><b>方向按分句判，不按短语判</b>：「新疆、西藏不发」里「不发」只挨着西藏，
 * 但店主的意思是两个都不发。所以先按「，；。换行」切分句、判方向，分句里再按「、和与及」切地名。
 *
 * <p><b>虚词只从两头剥</b>，不在中间删：「发展大道」里的「发」不能被当成「发货」剥掉。
 */
public final class ScopeTextParser {

    private ScopeTextParser() {
    }

    /** 一个地名短语。{@code text} 已去掉方向词与虚词 */
    public record Phrase(String text, boolean exclude) {
    }

    /** @param unlimited 出现了「全国 / 不限」 */
    public record Parsed(boolean unlimited, List<Phrase> phrases) {
    }

    private static final Pattern CLAUSE_SEP = Pattern.compile("[，,；;。.！!？?\\n\\r]+");
    /**
     * 分句里切地名。「和 / 与 / 及」**只在行政后缀之后**才算分隔：
     * 「龙华区和南山区」要切，「和平区」「和田」不能切。
     */
    private static final Pattern NAME_SEP = Pattern.compile(
            "[、/\\s]+|(?<=[区县市省镇乡道旗疆藏海夏西南北东])(以及|和|与|及|跟)");
    /** 「除新疆西藏外」：中间夹着地名的那种排除 */
    private static final Pattern EXCEPT = Pattern.compile("除.{1,40}?外");

    /**
     * 排除词。长的在前：先剥「不配送」再剥「不送」，否则剩下一个「配」。
     *
     * <p>单字的「除」「外」<b>不在这里</b>：「外环」「除州」这类地名里也有。
     * 「除」只在分句开头算（「除新疆西藏外」），「外」只在已判为排除的分句尾巴上剥。
     */
    private static final List<String> EXCLUDE_WORDS = List.of(
            "不配送", "不包括", "不包含", "不发货", "不送货", "不能送", "送不到",
            "不送", "不发", "不做", "不卖", "不含", "除了", "除外", "排除", "以外", "之外");

    /** 只在排除分句里剥的单字：「除新疆西藏外」——「除」只剥开头、「外」只剥结尾 */
    private static final List<String> EXCLUDE_EDGE = List.of("除", "外");

    /**
     * 「不限」的说法。「除了新疆西藏的**其他区域**」也是不限 —— 排除之外的地方都送。
     * 长的在前：先替换「其他地区」再替换「全国」，免得留下半截。
     */
    private static final List<String> UNLIMITED_WORDS = List.of(
            "全国各地", "全国", "不限地区", "不限", "所有地区", "全部地区",
            "其他区域", "其他地区", "其他地方", "其他省份", "其它区域", "其它地区", "其余地区", "其余区域", "其余省份");

    /** 两头可剥的虚词（长的在前） */
    private static final List<String> FILLERS = List.of(
            "都可以", "可以", "发货", "配送", "送货", "快递", "包邮", "范围", "地区", "全部", "整个", "所有",
            "只做", "只送", "只发", "仅限", "我们", "我家", "本店", "或者", "以及", "还有", "都", "均", "只", "仅");

    /** 只从尾巴剥的单字：从开头剥会伤地名（「发展大道」「到滘」） */
    private static final List<String> END_ONLY = List.of("等地区", "等地方", "等地", "等等", "等", "的", "送", "发", "做", "卖");

    /** 不该单独出现的残渣：剥完只剩这些就丢掉 */
    private static final Pattern NOISE = Pattern.compile("^[\\p{Punct}\\p{IsPunctuation}\\s]*$");

    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return new Parsed(false, List.of());
        }
        boolean unlimited = false;
        List<Phrase> out = new ArrayList<>();
        for (String raw : CLAUSE_SEP.split(text)) {
            String clause = raw.trim();
            if (clause.isEmpty()) {
                continue;
            }
            boolean exclude = EXCLUDE_WORDS.stream().anyMatch(clause::contains)
                    || clause.startsWith("除") || EXCEPT.matcher(clause).find();
            if (UNLIMITED_WORDS.stream().anyMatch(clause::contains)) {
                unlimited = true;
                for (String w : UNLIMITED_WORDS) {
                    clause = clause.replace(w, "、");
                }
            }
            for (String piece : NAME_SEP.split(clause)) {
                String name = strip(piece, exclude);
                if (!name.isEmpty() && !NOISE.matcher(name).matches()) {
                    out.add(new Phrase(name, exclude));
                }
            }
        }
        return new Parsed(unlimited, List.copyOf(out));
    }

    /** 从两头反复剥方向词与虚词，直到剥不动 */
    static String strip(String s, boolean exclude) {
        String cur = s == null ? "" : s.trim();
        boolean changed = true;
        while (changed && !cur.isEmpty()) {
            changed = false;
            for (List<String> words : exclude ? List.of(EXCLUDE_WORDS, EXCLUDE_EDGE, FILLERS, END_ONLY)
                    : List.of(EXCLUDE_WORDS, FILLERS, END_ONLY)) {
                if (words == EXCLUDE_EDGE) {
                    // 「除」只剥开头、「外」只剥结尾：「外环不送」的「外」是地名
                    if (cur.length() > 1 && cur.startsWith("除")) {
                        cur = cur.substring(1).trim();
                        changed = true;
                    }
                    if (cur.length() > 1 && cur.endsWith("外")) {
                        cur = cur.substring(0, cur.length() - 1).trim();
                        changed = true;
                    }
                    continue;
                }
                boolean endOnly = words == END_ONLY;
                for (String w : words) {
                    if (!endOnly && cur.length() > w.length() && cur.startsWith(w)) {
                        cur = cur.substring(w.length()).trim();
                        changed = true;
                    } else if (cur.length() > w.length() && cur.endsWith(w)) {
                        cur = cur.substring(0, cur.length() - w.length()).trim();
                        changed = true;
                    } else if (cur.equals(w)) {
                        cur = "";
                        changed = true;
                    }
                    if (cur.isEmpty()) {
                        return cur;
                    }
                }
            }
        }
        return cur;
    }

    // ------------------------------------------------------------------ 省简称

    /**
     * 省简称 → 两位码（「新疆」→ 65）。由全称去后缀得出，不另抄一份名单。
     *
     * <p>不用 {@code Provinces.codeOfName} 的前缀匹配：它对单字会误命中（「山」→ 山西）。
     * 这里只认<b>完整</b>的简称或全称。
     */
    static final Map<String, String> PROVINCE_SHORT = buildShort();

    private static Map<String, String> buildShort() {
        Map<String, String> m = new LinkedHashMap<>();
        for (var e : ai.neargo.shop.common.Provinces.NAME_BY_CODE.entrySet()) {
            String full = e.getValue();
            m.put(full, e.getKey());
            String s = full.replaceAll("(维吾尔自治区|壮族自治区|回族自治区|特别行政区|自治区|省|市)$", "");
            m.put(s, e.getKey());
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 一个短语正好是某个省（简称或全称）→ 两位码；否则 null */
    public static String provinceCode(String phrase) {
        return phrase == null ? null : PROVINCE_SHORT.get(phrase.trim());
    }

    /**
     * 粘连的几个省（「新疆西藏青海」）按简称贪心切开。<b>必须整串都切得完</b>才算 ——
     * 切到一半卡住就返回 null，交给别的规则，不留半截。
     */
    public static List<String> splitProvinces(String phrase) {
        if (phrase == null || phrase.length() < 4) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        int i = 0;
        while (i < phrase.length()) {
            String hit = null;
            for (int len = Math.min(10, phrase.length() - i); len >= 2; len--) {
                String code = PROVINCE_SHORT.get(phrase.substring(i, i + len));
                if (code != null) {
                    hit = code;
                    i += len;
                    break;
                }
            }
            if (hit == null) {
                return null;
            }
            codes.add(hit);
        }
        return codes.size() >= 2 ? codes : null;
    }
}
