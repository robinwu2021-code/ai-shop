package ai.neargo.shop.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多边形解析与「点在面内」精判（ADR-034 AC1 / AC12）。
 *
 * <p>坐标一律 E6 整数、顶点序 {@code [lngE6, latE6]}，与全库口径一致。
 */
class GeoPolygonTest {

    /** 深圳附近一块约 1 km × 1 km 的方块（lng 114.00→114.01, lat 22.50→22.51） */
    private static final String SQUARE =
            "[[114000000,22500000],[114010000,22500000],[114010000,22510000],[114000000,22510000]]";

    @Test
    @DisplayName("★★★ 面内在内、面外不在、边界算在内")
    void insideOutsideBoundary() {
        GeoPolygon g = GeoPolygon.parse(SQUARE, 200);
        assertThat(g.covers(22505000, 114005000)).as("中心点").isTrue();
        assertThat(g.covers(22520000, 114005000)).as("北边 1 km 外").isFalse();
        assertThat(g.covers(22505000, 113990000)).as("西边外").isFalse();
        assertThat(g.covers(22500000, 114005000)).as("南边界上：站在配送边界上应可买").isTrue();
        assertThat(g.covers(22500000, 114000000)).as("顶点上").isTrue();
        assertThat(g.vertexCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 凹多边形：凹口里的点不算在内，两臂上的点算")
    void concaveShape() {
        // U 形：外框 0..3000 × 0..3000，从顶部挖掉中间 1000..2000 × 1000..3000
        String u = "[[0,0],[3000,0],[3000,3000],[2000,3000],[2000,1000],[1000,1000],[1000,3000],[0,3000]]";
        GeoPolygon g = GeoPolygon.parse(u, 200);
        assertThat(g.covers(2000, 1500)).as("凹口中央（lat=2000,lng=1500）").isFalse();
        assertThat(g.covers(2000, 500)).as("左臂").isTrue();
        assertThat(g.covers(2000, 2500)).as("右臂").isTrue();
        assertThat(g.covers(500, 1500)).as("底部横梁").isTrue();
    }

    @Test
    @DisplayName("★★ 尾点与首点重复会被去掉；规范化后指纹稳定且长 32")
    void normalizationAndFingerprint() {
        GeoPolygon closed = GeoPolygon.parse("[[0,0],[1000,0],[1000,1000],[0,1000],[0,0]]", 200);
        GeoPolygon open = GeoPolygon.parse("[[0,0],[1000,0],[1000,1000],[0,1000]]", 200);
        assertThat(closed.vertexCount()).isEqualTo(4);
        assertThat(closed.fingerprint()).isEqualTo(open.fingerprint()).hasSize(32);
        assertThat(closed.normalizedJson()).isEqualTo(open.normalizedJson());
        // 换个顶点就是另一片范围：指纹必须变
        GeoPolygon moved = GeoPolygon.parse("[[0,0],[1000,0],[1000,1000],[0,1001]]", 200);
        assertThat(moved.fingerprint()).isNotEqualTo(open.fingerprint());
    }

    @Test
    @DisplayName("★★★ 顶点不足三个 / 坐标越界 / 坏 JSON / 超顶点上限 都拒，且说明原因")
    void rejectsInvalidInput() {
        for (String bad : List.of(
                "[[0,0],[1,1]]",                       // 两个点
                "[[200000000,0],[1,1],[2,2]]",         // 经度 200°
                "[[0,95000000],[1,1],[2,2]]",          // 纬度 95°
                "not json",
                "[[0,0],[0,0],[0,0]]")) {              // 三个点都重合，退化
            assertThatThrownBy(() -> GeoPolygon.parse(bad, 200))
                    .as("应拒：%s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageMatching(".+");
        }
        String tooMany = IntStream.range(0, 201)
                .mapToObj(i -> "[" + (i * 1000) + "," + ((i * 7919) % 100000) + "]")
                .collect(Collectors.joining(",", "[", "]"));
        assertThatThrownBy(() -> GeoPolygon.parse(tooMany, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @Test
    @DisplayName("★ 自相交的「蝴蝶结」经 buffer(0) 归一后仍可判定，不抛")
    void selfIntersectingIsNormalizedNotRejected() {
        // 两个三角形在中心点交叉：商家手画偶有自交，拒掉体验差；归一后判定仍有定义
        String bowtie = "[[0,0],[2000,2000],[2000,0],[0,2000]]";
        GeoPolygon g = GeoPolygon.parse(bowtie, 200);
        assertThat(g.vertexCount()).isGreaterThanOrEqualTo(3);
        // 左右两个三角形的内部点都应在内（归一成 MultiPolygon→取并集），交叉点外的空白不在内
        assertThat(g.covers(1000, 500)).as("左三角内部").isTrue();
        assertThat(g.covers(1000, 1500)).as("右三角内部").isTrue();
        assertThat(g.covers(1800, 1000)).as("上方空白").isFalse();
    }
}
