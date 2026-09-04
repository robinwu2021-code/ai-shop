package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.service.CommunityAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 位置分布（T12）。
 *
 * <p><b>这张表最要紧的不是那几行，是「算不了的」那一格。</b>
 * 没坐标的地址推不出聚落、有坐标却不落在任何围栏里的地址是开城线索、
 * 没标点的门店让自送半径形同虚设 —— 把它们静默丢掉，这张表就会把
 * 「缺数据」说成「缺需求」，而运营会据此去撤一个其实有人的片区的商家。
 *
 * <p>分母写错的分析比没有分析更危险：没有分析时人会去查，
 * 有一张看起来完整的表时，人会直接照着做。
 */
@SpringBootTest
@ActiveProfiles("test")
class CoverageDistributionTest {

    @Autowired
    private CommunityAdminService adminService;
    @Autowired
    private CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    private static int seq = 9500;

    private String community(int latE6, int lngE6, int fence) {
        var c = new CmtCommunity();
        c.setCommunityNo("DS" + seq++);
        c.setName("分布测试小区" + seq);
        c.setStatus("OPEN");
        c.setRegionCode("330106031");
        c.setKind(CmtCommunity.KIND_ESTATE);
        c.setFenceRadius(fence);
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
        return c.getCommunityNo();
    }

    private String address(Integer latE6, Integer lngE6) {
        var a = new ai.neargo.shop.user.entity.UsrAddress();
        a.setAddressId("DS-ADDR-" + seq++);
        a.setUserNo("U-DS-TEST");
        a.setName("分布测试");
        a.setPhone("13900000000");
        a.setDetail("测试地址");
        a.setLatE6(latE6);
        a.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> addressMapper.insert(a));
        return a.getAddressId();
    }

    private void dropAddresses(java.util.List<String> ids) {
        // 改了要还原：种子是全量测试共用的，留几行会让别处莫名其妙红
        DataScopeContext.executeWithoutScope(() -> addressMapper.delete(
                Wrappers.<ai.neargo.shop.user.entity.UsrAddress>lambdaQuery()
                        .in(ai.neargo.shop.user.entity.UsrAddress::getAddressId, ids)));
    }

    @Test
    @DisplayName("★★★ 「有坐标但不落在任何围栏里」要单列 —— 那是开城线索，不是「没需求」")
    void addressesOutsideEveryFenceAreCountedSeparately() {
        /*
         * 这一格最容易被静默丢掉：代码上「找不到归属就 continue」写起来最自然，
         * 而它的含义正好反过来 —— 那儿**真的有人**，只是平台还没在那儿开聚落。
         * 丢掉之后运营看到的是「那片没需求」，于是不去开城；
         * 而买家在那儿打开 App，看到的是一片空。
         */
        String near = community(30_500_000, 120_500_000, 500);
        var ids = new java.util.ArrayList<String>();
        try {
            ids.add(address(30_500_100, 120_500_000));                 // 圈内
            ids.add(address(31_500_000, 121_500_000));                 // 离所有聚落一百多公里
            var before = adminService.distribution();

            var row = before.rows().stream()
                    .filter(r -> r.communityNo().equals(near)).findFirst().orElseThrow();
            assertThat(row.buyerCount()).as("圈内那条要落到这一行上").isEqualTo(1);
            assertThat(before.unattributable().addressesOutsideFences())
                    .as("落在所有围栏之外的那条被静默丢掉了 = 「缺数据」被说成「缺需求」")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            dropAddresses(ids);
        }
    }

    @Test
    @DisplayName("★★★ 没坐标的地址算进「算不了的」，**不算进任何一行** —— 它推不出聚落，不是没人")
    void addressesWithoutCoordsAreNotSilentlyDropped() {
        var ids = new java.util.ArrayList<String>();
        try {
            var before = adminService.distribution();
            int beforeMissing = before.unattributable().addressesWithoutCoords();
            int beforeBuyers = before.rows().stream().mapToInt(r -> r.buyerCount()).sum();

            ids.add(address(null, null));

            var after = adminService.distribution();
            assertThat(after.unattributable().addressesWithoutCoords())
                    .as("没坐标的地址没被数进缺口 = 这张表的分母比真实的小，而它看起来很完整")
                    .isEqualTo(beforeMissing + 1);
            assertThat(after.rows().stream().mapToInt(r -> r.buyerCount()).sum())
                    .as("它不该落到任何一行上 —— 推不出聚落就是推不出，不能挑个最近的塞进去")
                    .isEqualTo(beforeBuyers);
        } finally {
            dropAddresses(ids);
        }
    }

    @Test
    @DisplayName("★★★ 归属走**层级优先于距离** —— 楼里的买家不该被算到隔壁小区头上")
    void buyersInsideABuildingCountForTheBuilding() {
        /*
         * 归属若按「最近的中心」取，站在 3 幢门口的买家会被算到隔壁小区头上：
         * 隔壁小区的中心可能确实更近。两边的数就都错了，而两个数看起来都合理。
         * 这条判据钉的是「分布表与 C 端 resolve 用的是同一套归属」。
         */
        String estate = community(30_510_000, 120_510_000, 1000);
        var b = new CmtCommunity();
        b.setCommunityNo("DS" + seq++);
        b.setName("分布测试楼栋");
        b.setStatus("OPEN");
        b.setRegionCode("330106031");
        b.setKind(CmtCommunity.KIND_BUILDING);
        b.setParentNo(estate);
        b.setFenceRadius(150);
        b.setLatE6(30_511_080);   // 离小区中心 ~120 米
        b.setLngE6(120_510_000);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(b));

        var ids = new java.util.ArrayList<String>();
        try {
            // 买家站在小区中心那个点上：离小区中心 0 米，离楼中心 ~120 米（在楼的 150 围栏内）
            ids.add(address(30_510_000, 120_510_000));
            var d = adminService.distribution();
            var building = d.rows().stream()
                    .filter(r -> r.communityNo().equals(b.getCommunityNo())).findFirst().orElseThrow();
            var parent = d.rows().stream()
                    .filter(r -> r.communityNo().equals(estate)).findFirst().orElseThrow();
            assertThat(building.buyerCount())
                    .as("楼里的买家算到了小区头上 = 分布表与 C 端 resolve 用的不是同一套归属")
                    .isEqualTo(1);
            assertThat(parent.buyerCount())
                    .as("对照量：同一个人不能在两行里各算一次").isZero();
        } finally {
            dropAddresses(ids);
            DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                    Wrappers.<CmtCommunity>lambdaQuery()
                            .eq(CmtCommunity::getCommunityNo, b.getCommunityNo())));
        }
    }

    @Test
    @DisplayName("★★ 关掉的聚落不进 rows，但要在「算不了的」里报出条数 —— 它的历史数据还在")
    void closedCommunitiesAreReportedNotHidden() {
        String open = community(30_520_000, 120_520_000, 500);
        var closed = new CmtCommunity();
        closed.setCommunityNo("DS" + seq++);
        closed.setName("分布测试已关闭");
        closed.setStatus("CLOSED");
        closed.setRegionCode("330106031");
        closed.setFenceRadius(500);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(closed));
        try {
            var d = adminService.distribution();
            assertThat(d.rows()).extracting("communityNo").contains(open).doesNotContain(closed.getCommunityNo());
            assertThat(d.unattributable().communitiesClosed()).isGreaterThanOrEqualTo(1);
        } finally {
            DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                    Wrappers.<CmtCommunity>lambdaQuery()
                            .eq(CmtCommunity::getCommunityNo, closed.getCommunityNo())));
        }
    }
}
