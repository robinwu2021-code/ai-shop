package ai.neargo.shop.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多边形 → S2 网格覆盖（ADR-034 AC1）。
 *
 * <p>覆盖集是超集、内部集是子集：于是「落在内部 cell ⇒ 一定在面内」「不落在任一覆盖 cell ⇒ 一定在面外」。
 * 边界 cell 只是「可能在内」，命中后由 {@link GeoPolygon#covers} 精判——这两条蕴含关系就是整个
 * 「网格做索引、几何做精判」方案的正确性所在，用固定种子的随机点逐条验。
 */
class S2CoverTest {

    /** 深圳湾一带一块不规则六边形，约 6 km × 8 km */
    private static final String IRREGULAR =
            "[[113960000,22470000],[114040000,22460000],[114065000,22505000],"
            + "[114030000,22560000],[113985000,22550000],[113955000,22510000]]";

    private static final int MIN = 12;
    private static final int MAX = 16;

    @Test
    @DisplayName("★★★ 内部 cell 命中的点必在面内；覆盖外的点必在面外（固定种子随机点）")
    void interiorImpliesInside_outsideCoverImpliesOutside() {
        GeoPolygon g = GeoPolygon.parse(IRREGULAR, 200);
        List<S2Cover.Cell> cells = S2Cover.cover(g, MIN, MAX, 256);
        Set<String> interior = cells.stream().filter(c -> !c.boundary()).map(S2Cover.Cell::token).collect(Collectors.toSet());
        Set<String> all = cells.stream().map(S2Cover.Cell::token).collect(Collectors.toSet());

        assertThat(cells).as("至少一个 cell").isNotEmpty();
        assertThat(cells.size()).as("不超过 maxCells").isLessThanOrEqualTo(256);
        assertThat(interior).as("km 级多边形必然有整个在内的 cell").isNotEmpty();
        assertThat(all.size()).as("边界 cell 也要有，否则精判那条路永远测不到").isGreaterThan(interior.size());

        Random rnd = new Random(42);
        int interiorHits = 0, outsideCover = 0;
        for (int i = 0; i < 1500; i++) {
            int lat = 22440000 + rnd.nextInt(140000);   // 22.44 .. 22.58
            int lng = 113940000 + rnd.nextInt(140000);  // 113.94 .. 114.08
            List<String> tokens = S2Cover.tokens(lat, lng, MIN, MAX);
            boolean inInterior = tokens.stream().anyMatch(interior::contains);
            boolean inAny = tokens.stream().anyMatch(all::contains);
            if (inInterior) {
                interiorHits++;
                assertThat(g.covers(lat, lng)).as("内部 cell ⇒ 在面内 (%d,%d)", lat, lng).isTrue();
            }
            if (!inAny) {
                outsideCover++;
                assertThat(g.covers(lat, lng)).as("覆盖外 ⇒ 不在面内 (%d,%d)", lat, lng).isFalse();
            }
        }
        assertThat(interiorHits).as("对照量：随机点里要真有落进内部 cell 的").isPositive();
        assertThat(outsideCover).as("对照量：也要真有落在覆盖外的").isPositive();
    }

    @Test
    @DisplayName("★★ 点的 token 按级别逐级给出，数量 = 级别数，且各级 token 互不相同")
    void tokensPerLevel() {
        List<String> t = S2Cover.tokens(22500000, 114000000, MIN, MAX);
        assertThat(t).hasSize(MAX - MIN + 1);
        assertThat(t.stream().distinct().count()).isEqualTo(t.size());
        assertThat(t).allMatch(s -> !s.isBlank());
    }

    @Test
    @DisplayName("★ 同一多边形两次覆盖结果一致（确定性，派生表可重建）")
    void coverIsDeterministic() {
        GeoPolygon g = GeoPolygon.parse(IRREGULAR, 200);
        assertThat(S2Cover.cover(g, MIN, MAX, 256)).isEqualTo(S2Cover.cover(g, MIN, MAX, 256));
    }
}
