package ai.neargo.shop.community.support;

/**
 * 坐标 → 格子。**固定地址库按格子匹配，不按坐标匹配。**
 *
 * <p>按坐标匹配等于永不命中：两个人站在同一扇门前，手机给出的经纬度也不会
 * 一模一样。格子把「差不多在一个地方」变成一个可以做等值查询的键 ——
 * 同一栋楼里所有人命中同一行，这是不频繁调用地图的第一道，也是最有效的一道。
 *
 * <p><b>精度 8 ≈ 38m × 19m</b>，建筑级够用。太细则命中率低、额度照花；
 * 太粗则一栋写字楼与隔壁小区共用一个名字。做成可配的，
 * <b>判据是上线后的命中率，不是格子大小本身</b>。
 *
 * <p>只做 encode：这张表从不需要「从格子反推坐标」—— 落库时存的是
 * 首次命中的那个真实点，比格子中心准。
 */
public final class Geohash {

    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

    private Geohash() {
    }

    /**
     * @param precision 字符数；8 约 38m×19m，7 约 153m×153m
     */
    public static String encode(int latE6, int lngE6, int precision) {
        double lat = latE6 / 1e6;
        double lng = lngE6 / 1e6;
        double[] latRange = {-90, 90};
        double[] lngRange = {-180, 180};
        StringBuilder out = new StringBuilder(precision);
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (out.length() < precision) {
            double[] range = even ? lngRange : latRange;
            double value = even ? lng : lat;
            double mid = (range[0] + range[1]) / 2;
            if (value > mid) {
                ch = (ch << 1) | 1;
                range[0] = mid;
            } else {
                ch = ch << 1;
                range[1] = mid;
            }
            even = !even;
            if (++bit == 5) {
                out.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return out.toString();
    }
}
