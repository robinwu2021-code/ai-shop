package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自提点匹配（TDD-C端位置选择-地址取代自提点 §M4a）。
 *
 * <p>买家选的是地址，自提点在下单那一刻按规则匹配。规则只放聚落域一处 ——
 * 它同时需要归属链、自提点坐标，和与围栏判定同一份的距离算法。
 * 下单那条路与「换点」那个列表若各排各的，两个顺序看起来都合理，
 * 只有买家跑错地方时才发现。
 */
@SpringBootTest
@ActiveProfiles("test")
class PickupMatchTest {

    @Autowired
    private CommunityQueryPort port;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;

    private static int seq = 9800;

    /** @param parentNo 非空即楼栋，挂在那个小区下 */
    private String community(String parentNo, int latE6, int lngE6, int fence) {
        var c = new CmtCommunity();
        c.setCommunityNo("PM" + seq++);
        c.setName((parentNo == null ? "匹配测试小区-" : "匹配测试楼栋-") + seq);
        c.setStatus("OPEN");
        c.setRegionCode("330106051");
        c.setKind(parentNo == null ? CmtCommunity.KIND_ESTATE : CmtCommunity.KIND_BUILDING);
        c.setParentNo(parentNo);
        c.setFenceRadius(fence);
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
        return c.getCommunityNo();
    }

    private String pickup(String communityNo, Integer latE6, Integer lngE6, String status) {
        var p = new CmtPickupPoint();
        p.setPickupNo("PMP" + seq++);
        p.setName("匹配测试点" + seq);
        p.setAddress("测试地址");
        p.setCommunityNo(communityNo);
        p.setType("STORE");
        p.setStatus(status);
        p.setLatE6(latE6);
        p.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.insert(p));
        return p.getPickupNo();
    }

    private void drop(java.util.List<String> pickupNos, java.util.List<String> communityNos) {
        // 改了要还原：种子是全量测试共用的，留几行会让别处莫名其妙红
        DataScopeContext.executeWithoutScope(() -> pickupMapper.delete(
                Wrappers.<CmtPickupPoint>lambdaQuery().in(CmtPickupPoint::getPickupNo, pickupNos)));
        DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                Wrappers.<CmtCommunity>lambdaQuery().in(CmtCommunity::getCommunityNo, communityNos)));
    }

    @Test
    @DisplayName("★★★ 归属链上**两级的点都是候选**，按离买家最近排")
    void chainPointsAreCandidatesOrderedByDistance() {
        /*
         * 站在楼里，链上有「这栋楼」和「它所在的小区」。只取最内层的话，
         * 楼里的人取不到小区门口那个点 —— 而那多半正是他平时去的地方。
         */
        int lat = 30_700_000;
        int lng = 120_700_000;
        String estate = community(null, lat, lng, 1000);
        String building = community(estate, lat + 400, lng, 150);
        String near = pickup(building, lat + 420, lng, "ACTIVE");      // 离买家 ~30 米
        String far = pickup(estate, lat + 1_000, lng, "ACTIVE");       // ~560 米
        try {
            var opts = port.pickupOptions(lat + 400, lng, java.util.Set.of());
            assertThat(opts).extracting("pickupNo")
                    .as("链上两级的点都要在候选里，且近的在前")
                    .containsExactly(near, far);
            assertThat(opts.get(0).distanceM()).isLessThan(opts.get(1).distanceM());
        } finally {
            drop(java.util.List.of(near, far), java.util.List.of(building, estate));
        }
    }

    @Test
    @DisplayName("★★★ 商家的许可点是过滤集；**空集视为不限**")
    void allowedActsAsFilterAndEmptyMeansUnrestricted() {
        int lat = 30_710_000;
        int lng = 120_710_000;
        String estate = community(null, lat, lng, 1000);
        String a = pickup(estate, lat + 100, lng, "ACTIVE");
        String b = pickup(estate, lat + 200, lng, "ACTIVE");
        try {
            assertThat(port.pickupOptions(lat, lng, java.util.Set.of(b)))
                    .extracting("pickupNo")
                    .as("许可集没起过滤作用 = 买家会被派到这家店根本不承接的点")
                    .containsExactly(b);
            assertThat(port.pickupOptions(lat, lng, java.util.Set.of()))
                    .extracting("pickupNo")
                    .as("空集当成「一个都不许」= 存量商家（从没配过取货点）发布当天一单都下不了")
                    .containsExactly(a, b);
        } finally {
            drop(java.util.List.of(a, b), java.util.List.of(estate));
        }
    }

    @Test
    @DisplayName("★★★ 非 ACTIVE 的点不进候选")
    void inactivePointsAreExcluded() {
        int lat = 30_720_000;
        int lng = 120_720_000;
        String estate = community(null, lat, lng, 1000);
        String active = pickup(estate, lat + 300, lng, "ACTIVE");
        String suspended = pickup(estate, lat + 50, lng, "SUSPENDED");  // 更近，但停用
        try {
            assertThat(port.pickupOptions(lat, lng, java.util.Set.of()))
                    .extracting("pickupNo")
                    .as("停用的点被派出去 = 买家到了门口才发现关着")
                    .containsExactly(active);
        } finally {
            drop(java.util.List.of(active, suspended), java.util.List.of(estate));
        }
    }

    @Test
    @DisplayName("★★ 没坐标的点排最后、距离给 -1 —— 不丢掉，也不谎称 0 米")
    void pointsWithoutCoordsGoLastWithMinusOne() {
        int lat = 30_730_000;
        int lng = 120_730_000;
        String estate = community(null, lat, lng, 1000);
        String located = pickup(estate, lat + 500, lng, "ACTIVE");
        String noCoords = pickup(estate, null, null, "ACTIVE");
        try {
            var opts = port.pickupOptions(lat, lng, java.util.Set.of());
            assertThat(opts).extracting("pickupNo")
                    .as("没坐标就丢掉 = 存量点（手填地址建的）全部失踪")
                    .containsExactly(located, noCoords);
            assertThat(opts.get(1).distanceM())
                    .as("给 0 的话端上会显示「0 米」，那是一句假话").isEqualTo(-1);
        } finally {
            drop(java.util.List.of(located, noCoords), java.util.List.of(estate));
        }
    }

    @Test
    @DisplayName("★★★ 落不进任何围栏时返回空 —— 给一个跑不到的点比不给更糟")
    void noChainMeansNoCandidates() {
        assertThat(port.pickupOptions(1_000_000, 1_000_000, java.util.Set.of()))
                .as("没有归属链却给出候选 = 那个点在几百公里外")
                .isEmpty();
        assertThat(port.pickupOptions(null, null, java.util.Set.of()))
                .as("没有坐标同理").isEmpty();
    }
}
