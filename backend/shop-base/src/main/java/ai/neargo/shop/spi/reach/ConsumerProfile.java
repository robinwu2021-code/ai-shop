package ai.neargo.shop.spi.reach;

import ai.neargo.shop.geo.S2Cover;

import java.util.ArrayList;
import java.util.List;

/**
 * 消费者「在哪儿」的匹配画像（ADR-034）。可见性判定只认这几个量，怎么推出来是调用方的事。
 *
 * <ul>
 *   <li>{@link #ancestors}：已知最精确区划码按国标嵌套截出的祖先集（省 2 / 市 4 / 区县 6 / 街道 9 / 村居 12 位）。
 *       范围项 {@code ref_code IN (ancestors)} 即命中 —— 五个行政级一条规则；
 *       消费者码比范围项粗时祖先集里没有那一级，自然不命中（「不知道在不在」≠「在」）。</li>
 *   <li>{@link #communityNos}：聚落号集合。单个消费者 = 「他所在的那个 + 它的上级」（点名小区要覆盖里面的楼栋）；
 *       区域画像（模糊定位只到区）= 该区域里的<b>全部</b>聚落号 —— 否则「只框了嘉逸花园」的商家
 *       在「福田区」下就不可见了，可嘉逸花园就在福田区。小区级范围项按它精确匹配。</li>
 *   <li>{@link #cellTokens}：坐标在 S2 {@code [minLevel, maxLevel]} 各级所在 cell 的 token，多边形范围按它们命中。</li>
 *   <li>{@link #latE6} / {@link #lngE6}：边界 cell 精判用。</li>
 * </ul>
 *
 * <p><b>确知即记，不臆造。</b>缺坐标就没有 token、缺区划码就没有祖先；判定侧按 fail-closed 处理排除项。
 * (0,0) 与越界坐标视为没坐标 —— 前者是端上没拿到定位时的典型值。
 */
public record ConsumerProfile(List<String> ancestors, List<String> communityNos,
                              List<String> cellTokens, Integer latE6, Integer lngE6) {

    /** 国标区划码各级长度 */
    private static final int[] LEVEL_LENGTHS = {2, 4, 6, 9, 12};

    public ConsumerProfile {
        ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
        communityNos = communityNos == null ? List.of()
                : communityNos.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
        cellTokens = cellTokens == null ? List.of() : List.copyOf(cellTokens);
    }

    /**
     * 组装一个<b>消费者</b>的画像。坐标只有两个都给且合法才算有；S2 token 按 {@code [minLevel, maxLevel]} 取。
     *
     * <p>聚落维度装「他所在的那个 + 它的上级」：点名小区的范围项要覆盖该小区里的楼栋。
     */
    public static ConsumerProfile of(String regionCode, String communityNo, String parentNo,
                                     Integer latE6, Integer lngE6, int minLevel, int maxLevel) {
        boolean coords = validCoords(latE6, lngE6);
        List<String> tokens = coords ? S2Cover.tokens(latE6, lngE6, minLevel, maxLevel) : List.of();
        return new ConsumerProfile(ancestorsOf(regionCode), java.util.Arrays.asList(communityNo, parentNo),
                tokens, coords ? latE6 : null, coords ? lngE6 : null);
    }

    /**
     * 一个<b>区域</b>的画像（模糊定位只落到区县时用）。
     *
     * <p>语义是「这个区域里<b>任一处</b>」——所以聚落维度装该区域里的<b>全部</b>聚落号、
     * cell 维度装它们各自的 cell token 之并。
     *
     * <p><b>为什么不能只给区划码</b>：那样只命中得了行政级范围项，而「只框了嘉逸花园」的商家
     * 在「福田区」的模糊定位下就不可见了 —— 可嘉逸花园就在福田区，他明明送得到。
     * 旧实现靠「展开该区的小区再逐个判」拿到这个结果，这里把那一步并进同一次点查。
     *
     * <p>区域没有单点坐标，所以 {@code latE6/lngE6} 为空：多边形的<b>内部</b> cell 命中仍然算
     * （那片确实在区域内），<b>边界</b> cell 无法精判则不算，排除型多边形按 fail-closed 处理 ——
     * 判不出来就不放行，宁可少卖。
     */
    public static ConsumerProfile ofArea(String regionCode, List<String> communityNos, List<String> cellTokens) {
        return ofArea(regionCode, communityNos, cellTokens, null, null);
    }

    /**
     * 区域画像 + <b>区域里已知的那个点</b>（模糊定位只落到区县、但同时有 GPS 坐标时用）。
     *
     * <p>★ <b>坐标是补充，不是替代</b>。2026-10-10 在生产上栽过一次：
     * {@code serving()} 原来按「有没有坐标」分流，于是端上同时传区划码与坐标的那个真实请求
     * （首页、分类页都是这样）走进了单点分支，**区域展开那一步被顶掉** ——
     * 「只框了嘉逸花园」的商家对「在福田区但没绑小区」的买家不可见了，首页从 11 件掉到 1 件。
     * 现在分流只看「有没有聚落号」：有坐标就把它并进区域画像，而不是改走另一条路。
     *
     * <p>带上坐标还多一件事：多边形的<b>边界</b> cell 能精判了（区域画像本来判不了，只能 fail-closed）。
     */
    public static ConsumerProfile ofArea(String regionCode, List<String> communityNos, List<String> cellTokens,
                                         Integer latE6, Integer lngE6) {
        boolean coords = validCoords(latE6, lngE6);
        return new ConsumerProfile(ancestorsOf(regionCode), communityNos, cellTokens,
                coords ? latE6 : null, coords ? lngE6 : null);
    }

    /** 2/4/6/9/12 位截取，只取不超过自身长度的整级 */
    public static List<String> ancestorsOf(String regionCode) {
        if (regionCode == null) {
            return List.of();
        }
        String code = regionCode.strip();
        if (code.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(LEVEL_LENGTHS.length);
        for (int len : LEVEL_LENGTHS) {
            if (code.length() >= len) {
                out.add(code.substring(0, len));
            }
        }
        return List.copyOf(out);
    }

    public boolean hasRegion() {
        return !ancestors.isEmpty();
    }

    public boolean hasCommunity() {
        return !communityNos.isEmpty();
    }

    public boolean hasCoords() {
        return latE6 != null && lngE6 != null;
    }

    /** 两个都给、在合法区间、且不是 (0,0) */
    public static boolean validCoords(Integer latE6, Integer lngE6) {
        if (latE6 == null || lngE6 == null) {
            return false;
        }
        if (latE6 == 0 && lngE6 == 0) {
            return false;
        }
        return latE6 >= -90_000_000 && latE6 <= 90_000_000 && lngE6 >= -180_000_000 && lngE6 <= 180_000_000;
    }
}
