package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.reach.ReachRule.Route;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新判定 {@link ReachRule#decide} 的规则表（ADR-034 AC1/AC5/AC13）。纯单元，不起 Spring。
 *
 * <p>每条规则一行。与旧 {@code covers} 最要紧的差别：<b>没有「没框范围 + 开快递 = 全平台」这条隐式分支</b>——
 * 虹选粮油「框了嘉逸花园却全平台可见」就是栽在那条上。
 */
class ReachDecideTest {

    private static final String EXPRESS = Fulfillments.EXPRESS;
    private static final String DELIVERY = Fulfillments.MERCHANT_DELIVERY;
    private static final String PICKUP = Fulfillments.STORE_PICKUP;

    /** 深圳福田某街道 + 坐标 */
    private static final ConsumerProfile FUTIAN = ConsumerProfile.of("440304001", "CM1", null, 22540000, 114060000, 12, 16);
    /** 只有区县码、没坐标没聚落（模糊定位） */
    private static final ConsumerProfile COARSE = ConsumerProfile.of("440304", null, null, null, null, 12, 16);
    /** 只有坐标，连区划都没解出来 */
    private static final ConsumerProfile COORDS_ONLY = ConsumerProfile.of(null, null, null, 22540000, 114060000, 12, 16);
    /** 什么都没有 */
    private static final ConsumerProfile NOTHING = ConsumerProfile.of(null, null, null, null, null, 12, 16);

    /** 精判一律通过 / 一律不通过的两个探针 */
    private static final PolygonProbe YES = (areaNo, p) -> true;
    private static final PolygonProbe NO = (areaNo, p) -> false;

    private static StoreMeta meta(List<Route> routes, boolean unlimited) {
        return new StoreMeta("E1", "S1", routes, unlimited, false, false, false);
    }

    private static StoreMeta meta(List<Route> routes, boolean unlimited,
                                  boolean adminEx, boolean cmtEx, boolean polyEx) {
        return new StoreMeta("E1", "S1", routes, unlimited, adminEx, cmtEx, polyEx);
    }

    private static StoreHits hits(Set<String> include, Set<String> exclude) {
        return new StoreHits(include, exclude, Set.of(), Set.of());
    }

    @Test
    @DisplayName("★★★ 没框任何范围的店对谁都不可见 —— 不再「没框 + 快递 = 全平台」")
    void unframedStoreReachesNobody() {
        for (String ch : List.of(EXPRESS, DELIVERY, PICKUP)) {
            StoreMeta s = meta(List.of(Route.all(ch)), false);
            assertThat(ReachRule.decide(s, StoreHits.empty(), FUTIAN, NO)).as(ch).isFalse();
            assertThat(ReachRule.decide(s, StoreHits.empty(), COARSE, NO)).as(ch).isFalse();
        }
    }

    @Test
    @DisplayName("★★★ 显式「不限」只对快递/自送为真；自提没有落点")
    void unlimitedOnlyForExpressAndDelivery() {
        assertThat(ReachRule.decide(meta(List.of(Route.all(EXPRESS)), true), StoreHits.empty(), FUTIAN, NO)).isTrue();
        assertThat(ReachRule.decide(meta(List.of(Route.all(DELIVERY)), true), StoreHits.empty(), FUTIAN, NO)).isTrue();
        assertThat(ReachRule.decide(meta(List.of(Route.all(PICKUP)), true), StoreHits.empty(), FUTIAN, NO))
                .as("自提「不限」无意义").isFalse();
        assertThat(ReachRule.decide(meta(List.of(Route.all(EXPRESS)), true), StoreHits.empty(), NOTHING, NO))
                .as("不限对「什么都不知道」的消费者也成立").isTrue();
    }

    @Test
    @DisplayName("★★★ 排除先算、永远赢 —— 即便同时有纳入命中（矛盾输入）")
    void excludeBeatsInclude() {
        StoreMeta s = meta(List.of(Route.all(EXPRESS)), true);
        assertThat(ReachRule.decide(s, hits(Set.of("a1"), Set.of("x1")), FUTIAN, NO)).isFalse();
        assertThat(ReachRule.decide(s, hits(Set.of("a1"), Set.of()), FUTIAN, NO)).as("对照：没排除就可见").isTrue();
    }

    @Test
    @DisplayName("★★★ fail-closed：有行政级排除而消费者没区划码 → 不可见；有多边形排除而没坐标 → 不可见")
    void failClosedWhenDimensionMissing() {
        StoreMeta adminEx = meta(List.of(Route.all(EXPRESS)), true, true, false, false);
        assertThat(ReachRule.decide(adminEx, StoreHits.empty(), COORDS_ONLY, NO))
                .as("只有坐标、判不出在不在排除的省里").isFalse();
        assertThat(ReachRule.decide(adminEx, StoreHits.empty(), COARSE, NO))
                .as("对照：有区划码就能判，没命中排除 → 按不限可见").isTrue();

        StoreMeta polyEx = meta(List.of(Route.all(EXPRESS)), true, false, false, true);
        assertThat(ReachRule.decide(polyEx, StoreHits.empty(), COARSE, NO))
                .as("没坐标、判不出在不在排除的多边形里").isFalse();
        assertThat(ReachRule.decide(polyEx, StoreHits.empty(), COORDS_ONLY, NO))
                .as("对照：有坐标就能判").isTrue();
    }

    @Test
    @DisplayName("★★ 聚落级排除不 fail-closed —— 否则纯定位用户看不到任何「排除了某栋楼」的店")
    void communityExcludeDoesNotFailClosed() {
        StoreMeta s = meta(List.of(Route.all(EXPRESS)), true, false, true, false);
        assertThat(ReachRule.decide(s, StoreHits.empty(), COORDS_ONLY, NO)).isTrue();
        assertThat(ReachRule.decide(s, StoreHits.empty(), NOTHING, NO)).isTrue();
    }

    @Test
    @DisplayName("★★★ 边界 cell 要精判：纳入侧精判不过则不算命中，排除侧精判过则出局")
    void boundaryCellsNeedExactCheck() {
        StoreMeta s = meta(List.of(Route.all(EXPRESS)), false);
        StoreHits boundaryInclude = new StoreHits(Set.of(), Set.of(), Set.of("p1"), Set.of());
        assertThat(ReachRule.decide(s, boundaryInclude, FUTIAN, NO)).as("精判不过 = 没命中").isFalse();
        assertThat(ReachRule.decide(s, boundaryInclude, FUTIAN, YES)).as("精判通过 = 命中").isTrue();

        StoreMeta withPolyEx = meta(List.of(Route.all(EXPRESS)), false, false, false, true);
        StoreHits incPlusBoundaryEx = new StoreHits(Set.of("a1"), Set.of(), Set.of(), Set.of("px"));
        assertThat(ReachRule.decide(withPolyEx, incPlusBoundaryEx, FUTIAN, YES)).as("边界排除精判过 → 出局").isFalse();
        assertThat(ReachRule.decide(withPolyEx, incPlusBoundaryEx, FUTIAN, NO)).as("精判不过 → 纳入仍成立").isTrue();
    }

    @Test
    @DisplayName("★★ SUBSET 路只认它勾的那几条纳入项；「不限」不参与子集")
    void subsetRouteOnlyCountsItsPicks() {
        StoreMeta s = meta(List.of(new Route(EXPRESS, Set.of("a1"))), false);
        assertThat(ReachRule.decide(s, hits(Set.of("a1"), Set.of()), FUTIAN, NO)).isTrue();
        assertThat(ReachRule.decide(s, hits(Set.of("a2"), Set.of()), FUTIAN, NO)).as("命中的不是它勾的").isFalse();

        StoreMeta unlimitedSubset = meta(List.of(new Route(EXPRESS, Set.of("a1"))), true);
        assertThat(ReachRule.decide(unlimitedSubset, StoreHits.empty(), FUTIAN, NO))
                .as("子集路不吃「不限」").isFalse();

        StoreMeta emptySubset = meta(List.of(new Route(EXPRESS, Set.of())), false);
        assertThat(ReachRule.decide(emptySubset, hits(Set.of("a1"), Set.of()), FUTIAN, NO))
                .as("一块没勾 = 这一路哪儿都不送").isFalse();
    }

    @Test
    @DisplayName("★★ 任一路可达即可见：自提不通、快递通 → 可见")
    void anyRouteIsEnough() {
        StoreMeta s = meta(List.of(Route.all(PICKUP), Route.all(EXPRESS)), true);
        assertThat(ReachRule.decide(s, StoreHits.empty(), FUTIAN, NO)).isTrue();
    }

    @Test
    @DisplayName("★★ selectable：自提选「全部」不看买家在哪；但排除照样先减")
    void selectablePickupIgnoresWhereBuyerLives() {
        StoreMeta s = meta(List.of(Route.all(PICKUP)), false);
        Route pickup = Route.all(PICKUP);
        assertThat(ReachRule.selectable(s, StoreHits.empty(), FUTIAN, pickup, NO))
                .as("自提全部路：没框范围也可选").isTrue();
        assertThat(ReachRule.selectable(s, hits(Set.of(), Set.of("x1")), FUTIAN, pickup, NO))
                .as("排除命中仍不可选").isFalse();

        StoreMeta adminEx = meta(List.of(Route.all(PICKUP)), false, true, false, false);
        assertThat(ReachRule.selectable(adminEx, StoreHits.empty(), COORDS_ONLY, pickup, NO))
                .as("fail-closed 也适用于 selectable").isFalse();
    }

    @Test
    @DisplayName("★ 空参数不炸：meta/profile 为 null 返回不可见")
    void nullSafe() {
        assertThat(ReachRule.decide(null, StoreHits.empty(), FUTIAN, NO)).isFalse();
        assertThat(ReachRule.decide(meta(List.of(Route.all(EXPRESS)), true), null, FUTIAN, NO))
                .as("hits 为 null 按空命中处理，仍能靠「不限」可见").isTrue();
        assertThat(ReachRule.decide(meta(List.of(Route.all(EXPRESS)), true), StoreHits.empty(), null, NO)).isFalse();
    }
}
