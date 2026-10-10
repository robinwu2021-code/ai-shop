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
 *   <li>{@link #communityNo} / {@link #parentNo}：落到的聚落及其上级（楼栋→小区），小区级范围按它们精确匹配。</li>
 *   <li>{@link #cellTokens}：坐标在 S2 {@code [minLevel, maxLevel]} 各级所在 cell 的 token，多边形范围按它们命中。</li>
 *   <li>{@link #latE6} / {@link #lngE6}：边界 cell 精判用。</li>
 * </ul>
 *
 * <p><b>确知即记，不臆造。</b>缺坐标就没有 token、缺区划码就没有祖先；判定侧按 fail-closed 处理排除项。
 * (0,0) 与越界坐标视为没坐标 —— 前者是端上没拿到定位时的典型值。
 */
public record ConsumerProfile(List<String> ancestors, String communityNo, String parentNo,
                              List<String> cellTokens, Integer latE6, Integer lngE6) {

    /** 国标区划码各级长度 */
    private static final int[] LEVEL_LENGTHS = {2, 4, 6, 9, 12};

    public ConsumerProfile {
        ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
        cellTokens = cellTokens == null ? List.of() : List.copyOf(cellTokens);
        communityNo = blankToNull(communityNo);
        parentNo = blankToNull(parentNo);
    }

    /**
     * 组装画像。坐标只有两个都给且合法才算有；S2 token 按 {@code [minLevel, maxLevel]} 取。
     */
    public static ConsumerProfile of(String regionCode, String communityNo, String parentNo,
                                     Integer latE6, Integer lngE6, int minLevel, int maxLevel) {
        boolean coords = validCoords(latE6, lngE6);
        List<String> tokens = coords ? S2Cover.tokens(latE6, lngE6, minLevel, maxLevel) : List.of();
        return new ConsumerProfile(ancestorsOf(regionCode), communityNo, parentNo, tokens,
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
        return communityNo != null;
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
