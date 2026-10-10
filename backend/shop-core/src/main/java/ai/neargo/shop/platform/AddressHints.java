package ai.neargo.shop.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「深圳市龙华区福安雅园」这类**带上级的写法**切成 ["深圳市", "龙华区", "福安雅园"]。
 *
 * <p>人打字时习惯从大到小写全，而无论是库内搜索（{@code sys_region}）还是问地图
 * （高德 {@code inputtips}/{@code place/around}），都只认「目标 + 城市偏好」两件事，
 * 不认一整句地址。这个类只做一件事：把一句话切成有意义的几段，供两边各取所需——
 * 库内搜索要**每一段都验**（见 {@code RegionServiceImpl.matchesAncestors}），
 * 问地图只需要**最后一段当关键词、最靠前的市级段当 city**，宽松得多。
 *
 * <p>只按<b>行政后缀</b>切，不做分词：后缀是地名自带的，切错的代价（多一段约束）
 * 远小于分词切错（把「龙华」切成「龙」「华」，一条都对不上）。
 */
public final class AddressHints {

    /**
     * **「区」前面不能是「社」或「小」**：「新苑社区居委会」「阳光小区」里的「区」
     * 是「社区/小区」这个词的一部分，不是区县级边界——不排掉的话，
     * 「新苑社区居委会」会被切成 ["新苑社区", "居委会"]，库内搜索把「新苑社区」
     * 当成祖先约束去验，而这个词根本不是任何一级区划的名字，验不过，整条搜索白搭
     * （真机撞过：搜带「社区」二字的官方全名，一条也搜不到）。
     */
    private static final Pattern SEGMENT =
            Pattern.compile(".+?(省|市|自治区|自治州|地区|盟|(?<!社)(?<!小)区|县|旗|街道|镇|乡)");
    private static final Pattern CITY_SUFFIX = Pattern.compile("(市|自治州|地区|盟)$");

    private AddressHints() {
    }

    public static List<String> segments(String kw) {
        List<String> out = new ArrayList<>();
        if (kw == null) {
            return out;
        }
        Matcher m = SEGMENT.matcher(kw);
        int end = 0;
        while (m.find()) {
            out.add(m.group());
            end = m.end();
        }
        if (end < kw.length()) {
            out.add(kw.substring(end));
        }
        return out;
    }

    /** 最后一段：问地图时真正要搜的关键词。只有一段时就是整句本身 */
    public static String target(List<String> segs) {
        return segs.isEmpty() ? "" : segs.get(segs.size() - 1);
    }

    /** 最靠前的市级段，给 {@code inputtips}/{@code around} 当 city 偏好用；没有就是 null */
    public static String cityHint(List<String> segs) {
        return segs.stream().filter(s -> CITY_SUFFIX.matcher(s).find()).findFirst().orElse(null);
    }
}
