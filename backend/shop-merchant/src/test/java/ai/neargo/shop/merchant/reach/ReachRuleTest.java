package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.reach.ReachRule.Area;
import ai.neargo.shop.merchant.reach.ReachRule.Route;
import ai.neargo.shop.merchant.reach.ReachRule.StoreReach;
import ai.neargo.shop.spi.user.CommunityQueryPort.CommunityRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「送不送得到」的判定表（方案-商品可见性改查询时关联 §2.3）。
 *
 * <p>每条规则一行；四处「此前展示与结算答案不同」的情形各一行，断言统一后的答案。
 * 主体 / 门店是否 ACTIVE 不归这里管（状态闸由调用方把守），见 ReachRule 类注释。
 */
class ReachRuleTest {

    // 小区：福田（4403 04 004）两个开放的、一个未开放的；运城一个；C1 下面两栋楼
    static final CommunityRef C1 = new CommunityRef("C1", "440304004", null, true);
    static final CommunityRef C2 = new CommunityRef("C2", "140802001", null, true);
    static final CommunityRef C3_CLOSED = new CommunityRef("C3", "440304004", null, false);
    static final CommunityRef B1 = new CommunityRef("B1", "440304004", "C1", true);
    static final CommunityRef B2_CLOSED = new CommunityRef("B2", "440304004", "C1", false);
    static final CommunityRef NO_REGION = new CommunityRef("C4", null, null, true);

    static final Area FUTIAN = new Area("A1", "DISTRICT", "440304");
    static final Area YUNCHENG = new Area("A2", "DISTRICT", "140802");
    static final Area AT_C1 = new Area("A3", ReachRule.LEVEL_COMMUNITY, "C1");
    static final Area AT_C3 = new Area("A4", ReachRule.LEVEL_COMMUNITY, "C3");

    static final String EXPRESS = Fulfillments.EXPRESS;
    static final String DELIVERY = Fulfillments.MERCHANT_DELIVERY;
    static final String PICKUP = Fulfillments.STORE_PICKUP;

    static StoreReach store(List<Route> routes, List<Area> includes, List<Area> excludes) {
        return new StoreReach("E1", "S1", routes, includes, excludes);
    }

    static Route subset(String channel, String... areaNos) {
        return new Route(channel, Set.of(areaNos));
    }

    // ---- R4 / R7：没框范围时「不限」与否由送货方式决定

    @Test
    void unframedExpressOrDeliveryReachesEveryOpenCommunity() {
        for (String ch : List.of(EXPRESS, DELIVERY)) {
            StoreReach s = store(List.of(Route.all(ch)), List.of(), List.of());
            assertTrue(ReachRule.covers(s, C1), ch);
            assertTrue(ReachRule.covers(s, C2), ch);
            assertFalse(ReachRule.covers(s, C3_CLOSED), ch + " 未开放的小区不在「全部开放」里");
        }
    }

    @Test
    void unframedPickupReachesNobody() {
        StoreReach s = store(List.of(Route.all(PICKUP)), List.of(), List.of());
        assertFalse(ReachRule.covers(s, C1));
        assertFalse(ReachRule.covers(s, C2));
    }

    // ---- 框了 INCLUDE：快递也尊重框选（#4②）

    @Test
    void framedRangeBindsEveryRouteIncludingExpress() {
        for (String ch : List.of(EXPRESS, DELIVERY, PICKUP)) {
            StoreReach s = store(List.of(Route.all(ch)), List.of(FUTIAN), List.of());
            assertTrue(ReachRule.covers(s, C1), ch);
            assertFalse(ReachRule.covers(s, C2), ch + " 框了福田，运城就不在范围里");
        }
    }

    // ---- R5 / R10：EXCLUDE 优先、对每一路一视同仁，排除小区连它的楼栋一起排除

    @Test
    void excludeWinsOnEveryRoute() {
        StoreReach unframed = store(List.of(Route.all(EXPRESS)), List.of(), List.of(FUTIAN));
        assertFalse(ReachRule.covers(unframed, C1), "快递不限范围，也减得掉排除");
        assertTrue(ReachRule.covers(unframed, C2));

        StoreReach sub = store(List.of(subset(DELIVERY, "A1")), List.of(FUTIAN), List.of(AT_C1));
        assertFalse(ReachRule.covers(sub, C1));
        assertFalse(ReachRule.covers(sub, B1), "排除小区 = 排除它的楼栋");
    }

    // ---- R6：SUBSET 只看这一路勾的、且要与主体 INCLUDE 取交

    @Test
    void subsetIsIntersectedWithEntityIncludes() {
        StoreReach s = store(List.of(subset(DELIVERY, "A1")), List.of(FUTIAN, YUNCHENG), List.of());
        assertTrue(ReachRule.covers(s, C1));
        assertFalse(ReachRule.covers(s, C2), "运城在主体范围里，但这一路没勾");

        StoreReach dangling = store(List.of(subset(DELIVERY, "A-GONE")), List.of(FUTIAN), List.of());
        assertFalse(ReachRule.covers(dangling, C1), "子集引用了主体已经没有的范围 —— 不算");
    }

    // ---- R8：直接点名的小区不检查开放；楼栋只取开放的、只往下一层

    @Test
    void namedCommunityIgnoresOpenButItsBuildingsMustBeOpen() {
        StoreReach closed = store(List.of(Route.all(DELIVERY)), List.of(AT_C3), List.of());
        assertTrue(ReachRule.covers(closed, C3_CLOSED), "点名的小区本身不看是否开放");

        StoreReach s = store(List.of(Route.all(DELIVERY)), List.of(AT_C1), List.of());
        assertTrue(ReachRule.covers(s, C1));
        assertTrue(ReachRule.covers(s, B1), "开放的楼栋跟着小区一起纳入");
        assertFalse(ReachRule.covers(s, B2_CLOSED), "未开放的楼栋不纳入");
        assertFalse(ReachRule.covers(s, C2));
    }

    // ---- R9：区划前缀只取开放小区；空前缀、没有区划码都不命中

    @Test
    void regionPrefixMatchesOpenCommunitiesOnly() {
        StoreReach s = store(List.of(Route.all(DELIVERY)), List.of(FUTIAN), List.of());
        assertFalse(ReachRule.covers(s, C3_CLOSED));
        assertFalse(ReachRule.covers(s, NO_REGION));

        StoreReach blank = store(List.of(Route.all(DELIVERY)), List.of(new Area("A9", "CITY", " ")), List.of());
        assertFalse(ReachRule.covers(blank, C1), "框了个空区划不能变成覆盖全平台");
    }

    // ---- 此前展示与结算答案不同的四处，统一后的答案

    @Test
    void emptySubsetSendsNowhere() {
        // 此前展示：裁剪后为空 → 落到「自送没框 = 不限」→ 全部开放小区
        StoreReach s = store(List.of(subset(DELIVERY)), List.of(FUTIAN), List.of());
        assertFalse(ReachRule.covers(s, C1));
        assertFalse(ReachRule.covers(s, C2));
        StoreReach noIncludes = store(List.of(subset(DELIVERY)), List.of(), List.of());
        assertFalse(ReachRule.covers(noIncludes, C2), "主体没框范围时，空子集同样哪儿都不送");
    }

    @Test
    void eachRouteKeepsItsOwnScope() {
        // 此前展示：店里只要有一路 SUBSET，整家店都被裁成那个子集，快递的「全部」不起作用
        StoreReach s = store(List.of(Route.all(EXPRESS), subset(DELIVERY, "A2")),
                List.of(FUTIAN, YUNCHENG), List.of());
        assertTrue(ReachRule.covers(s, C1), "快递选「全部」= 主体范围，福田照样送得到");
        assertTrue(ReachRule.covers(s, C2));
        assertFalse(ReachRule.covers(s, s.routes().get(1), C1), "自送只在它勾的运城");
        assertTrue(ReachRule.covers(s, s.routes().get(1), C2));
    }

    @Test
    void subsetNamingACommunityCoversItsBuildings() {
        // 此前结算：子集只认小区本身，楼里的买家结算时这一路不可选
        StoreReach s = store(List.of(subset(DELIVERY, "A3")), List.of(AT_C1), List.of());
        assertTrue(ReachRule.selectable(s, s.routes().get(0), B1));
    }

    @Test
    void checkoutAlsoHonoursExclude() {
        // 此前结算：不减 EXCLUDE
        StoreReach s = store(List.of(Route.all(DELIVERY), Route.all(PICKUP)), List.of(), List.of(AT_C1));
        assertFalse(ReachRule.selectable(s, s.routes().get(0), C1));
        assertFalse(ReachRule.selectable(s, s.routes().get(1), C1), "自提也一样：商家明说了不服务那里");
    }

    // ---- 结算与展示唯一的差别：自提选「全部」不看买家小区

    @Test
    void pickupOverAllIsSelectableWhereverBuyerLives() {
        StoreReach s = store(List.of(Route.all(DELIVERY), Route.all(PICKUP)), List.of(), List.of());
        Route pickup = s.routes().get(1);
        assertFalse(ReachRule.covers(s, pickup, C1), "展示上，自提没框 = 没有落点");
        assertTrue(ReachRule.selectable(s, pickup, C1), "结算时，自提的约束在取货点上，不在买家小区");

        StoreReach sub = store(List.of(subset(PICKUP, "A1")), List.of(FUTIAN, YUNCHENG), List.of());
        assertFalse(ReachRule.selectable(sub, sub.routes().get(0), C2), "自提选了子集就按子集");
    }

    // ---- 「不限地区」（买家详情页）与正向展开

    /**
     * 「不限地区」的判据在 ADR-034 改了：从「<b>没框</b>任何 INCLUDE + 开了快递/自送」
     * 改成「有一条<b>显式的</b> {@code UNLIMITED} 纳入项 + 开了快递/自送」。
     *
     * <p>为什么要改：「没框」有四种成因（框写到别家店、没物化成行、框成了 EXCLUDE、门店级错位），
     * 任何一种都会让商家在不知情的情况下铺满全平台 —— 虹选粮油「框了嘉逸花园却全平台可见」就是这么来的，
     * 而且不报错。改成显式之后，存量那批由 V397 按旧语义逐字回填，行为不变但从此看得见、改得掉。
     */
    static final Area UNLIMITED_ITEM = new Area("A9",
            ai.neargo.shop.merchant.entity.MchServiceArea.LEVEL_UNLIMITED,
            ai.neargo.shop.merchant.entity.MchServiceArea.UNLIMITED_REF);

    @Test
    void unlimitedNeedsAnExplicitItemPlusAnOpenEndedRoute() {
        assertTrue(ReachRule.unlimited(store(List.of(Route.all(EXPRESS)), List.of(UNLIMITED_ITEM), List.of())));
        assertTrue(ReachRule.unlimited(store(List.of(Route.all(DELIVERY)), List.of(UNLIMITED_ITEM), List.of())));
        assertFalse(ReachRule.unlimited(store(List.of(Route.all(PICKUP)), List.of(UNLIMITED_ITEM), List.of())),
                "自提没有落点，「不限」对它无意义");
        assertFalse(ReachRule.unlimited(store(List.of(Route.all(EXPRESS)), List.of(), List.of())),
                "一条范围项都没有 ≠ 不限（消融：恢复旧判据这一行就红）");
        assertFalse(ReachRule.unlimited(store(List.of(Route.all(EXPRESS)), List.of(FUTIAN), List.of())),
                "框了具体范围就按框选卖 —— 快递也一样");
        assertFalse(ReachRule.unlimited(store(List.of(subset(DELIVERY, "A1")), List.of(UNLIMITED_ITEM), List.of())),
                "子集路不吃「不限」：子集说的是「这一路只服务我框的其中几块」");
    }

    @Test
    void reachableKeepsCandidateOrderAndDedupes() {
        StoreReach s = store(List.of(Route.all(DELIVERY)), List.of(FUTIAN, AT_C3), List.of());
        assertEquals(List.of("C1", "B1", "C3"),
                ReachRule.reachable(s, List.of(C1, C2, B1, C1, C3_CLOSED, B2_CLOSED)));
        assertEquals(Set.of("C3"), ReachRule.namedCommunities(s));
    }
}
