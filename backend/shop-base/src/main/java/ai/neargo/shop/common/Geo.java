package ai.neargo.shop.common;

/**
 * 围栏判定用的平面近似距离。
 *
 * <p><b>这一份是唯一的一份，别再复制。</b> 围栏影响预览（「改半径会多/少覆盖多少人」）
 * 与围栏判定本身必须用同一个算法 —— 各写一遍的下场不是报错，是运营看着预览说
 * 「会多进来 3 户」，改完发现是 2 户，而两个数字都「算对了」，只是算的不是同一件事。
 * 那种偏差没人查得动：它只在边界那一圈上出现。
 */
public final class Geo {

    private Geo() {
    }

    /** 一个纬度差对应的米数。**111000 不是 111320** —— 这是围栏一直在用的那个值，不许顺手改精确 */
    private static final double METERS_PER_DEGREE = 111_000d;

    /**
     * a 到 b 的近似米数。任一坐标为空返回 0（= 判定为「在圈内」，与围栏原有行为逐字相同）。
     *
     * <p>⚠️ <b>经度收缩用的是 b 点的纬度，不是中点</b>。看着不对称，而它是围栏判定
     * 一直以来的算法：换成中点会让每一条边界上的地址重新分类一次，
     * 而那是一次没有任何人通知的可见性变更。差值在这些纬度上是厘米级，
     * 不值得拿「谁在圈里」去换。
     */
    public static int meters(Integer aLatE6, Integer aLngE6, Integer bLatE6, Integer bLngE6) {
        if (aLatE6 == null || aLngE6 == null || bLatE6 == null || bLngE6 == null) {
            return 0;
        }
        double dLat = (aLatE6 - bLatE6) / 1e6 * METERS_PER_DEGREE;
        double dLng = (aLngE6 - bLngE6) / 1e6 * METERS_PER_DEGREE * Math.cos(Math.toRadians(bLatE6 / 1e6));
        return (int) Math.round(Math.hypot(dLat, dLng));
    }
}
