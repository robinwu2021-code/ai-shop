package ai.neargo.shop.geo;

import com.google.common.geometry.S2CellId;
import com.google.common.geometry.S2CellUnion;
import com.google.common.geometry.S2LatLng;
import com.google.common.geometry.S2Loop;
import com.google.common.geometry.S2Point;
import com.google.common.geometry.S2Polygon;
import com.google.common.geometry.S2RegionCoverer;

import java.util.ArrayList;
import java.util.List;

/**
 * 多边形 ↔ S2 网格（ADR-034）。
 *
 * <p>把几何问题变成集合成员问题：保存时把多边形离散成若干 cell 写进派生表，
 * 查询时消费者坐标取各级父 cell 的 token，{@code cell_id IN (...)} —— 与行政级的「祖先码 IN」同一种索引形状，
 * MySQL / H2 / 内存三处完全一致，不需要任何空间 SQL。
 *
 * <p><b>覆盖集是超集、内部集是子集</b>：
 * <ul>
 *   <li>落在<b>内部 cell</b> ⇒ 一定在面内，命中即在内；</li>
 *   <li>落在<b>边界 cell</b> ⇒ 可能在内，还要用 {@link GeoPolygon#covers} 精判；</li>
 *   <li>不落在任一覆盖 cell ⇒ 一定在面外。</li>
 * </ul>
 * 精判只发生在边界 cell 上，量很小。
 *
 * <p>级别区间与 cell 上限由 {@code shop.reach.*} 配置（默认 12..16，边长约 2 km → 150 m，≤256 个）。
 * 参数改了可用 {@code rebuildAll} 全量重算 —— 派生表本来就该能重建。
 */
public final class S2Cover {

    /** 一个覆盖 cell。{@code boundary} 为真表示只是「可能在内」 */
    public record Cell(String token, int level, boolean boundary) {
    }

    private S2Cover() {
    }

    /**
     * 多边形的网格覆盖。结果确定（同一多边形两次结果相同），按覆盖顺序返回。
     *
     * <p>只取外环：商家画的是一个闭合范围，没有洞；自交归一出的多块（MultiPolygon）各块分别算外环后并集。
     */
    public static List<Cell> cover(GeoPolygon polygon, int minLevel, int maxLevel, int maxCells) {
        S2Polygon region = toS2(polygon);
        S2RegionCoverer coverer = S2RegionCoverer.builder()
                .setMinLevel(minLevel)
                .setMaxLevel(maxLevel)
                .setMaxCells(maxCells)
                .build();
        S2CellUnion covering = coverer.getCovering(region);
        S2CellUnion interior = coverer.getInteriorCovering(region);
        /*
         * ⚠️ minLevel 对 coverer 只是软约束：为了省 cell 它会给出比 minLevel 更粗的格
         * （实测 12..16 的配置返回了一个 level 11）。而查询侧 tokens() 只取 [min,max] 各级父 cell，
         * 更粗的格永远对不上 —— 整片范围在那一格里的消费者就被静默漏掉。
         * 所以把覆盖集 denormalize 到 minLevel：粗格拆成 minLevel 的子格，让「库里 cell 级别 ∈ [min,max]」
         * 成为硬契约。代价是 cell 数可能略超 maxCells（软上限），可接受。
         */
        ArrayList<S2CellId> leveled = new ArrayList<>();   // S2 的签名要求 ArrayList
        covering.denormalize(minLevel, 1, leveled);
        List<Cell> out = new ArrayList<>(leveled.size());
        for (S2CellId id : leveled) {
            out.add(new Cell(id.toToken(), id.level(), !interior.contains(id)));
        }
        return out;
    }

    /** 一个点在 {@code [minLevel, maxLevel]} 各级所在 cell 的 token，供 {@code cell_id IN (...)} */
    public static List<String> tokens(int latE6, int lngE6, int minLevel, int maxLevel) {
        S2CellId leaf = S2CellId.fromLatLng(S2LatLng.fromE6(latE6, lngE6));
        List<String> out = new ArrayList<>(maxLevel - minLevel + 1);
        for (int level = minLevel; level <= maxLevel; level++) {
            out.add(leaf.parent(level).toToken());
        }
        return out;
    }

    // ── JTS → S2 ───────────────────────────────────────────────────────

    private static S2Polygon toS2(GeoPolygon polygon) {
        org.locationtech.jts.geom.Geometry g = polygon.jts();
        List<S2Loop> loops = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            org.locationtech.jts.geom.Polygon part = (org.locationtech.jts.geom.Polygon) g.getGeometryN(i);
            loops.add(loopOf(part.getExteriorRing().getCoordinates()));
        }
        if (loops.size() == 1) {
            return new S2Polygon(loops.get(0));
        }
        // 多块并集：逐块建多边形再合并，避免把互不相交的外环当成「带洞」解释
        S2Polygon union = new S2Polygon(loops.get(0));
        for (int i = 1; i < loops.size(); i++) {
            S2Polygon next = new S2Polygon(loops.get(i));
            S2Polygon merged = new S2Polygon();
            merged.initToUnion(union, next);
            union = merged;
        }
        return union;
    }

    /** JTS 环（闭合、x=lng y=lat 度）→ S2Loop（去掉重复的闭合点，normalize 保证面积 ≤ 半球） */
    private static S2Loop loopOf(org.locationtech.jts.geom.Coordinate[] ring) {
        int n = ring.length;
        if (n > 1 && ring[0].equals2D(ring[n - 1])) {
            n--;
        }
        List<S2Point> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            pts.add(S2LatLng.fromDegrees(ring[i].y, ring[i].x).toPoint());
        }
        S2Loop loop = new S2Loop(pts);
        loop.normalize();
        return loop;
    }
}
