package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.community.service.CommunityAdminService.DistributionVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 位置分布：**区县概览 + 聚落下钻**（TDD-运营端位置分布-区县下钻；需求 PRD-位置与经营范围 O11–O13 §8.3）。
 *
 * <p>这屏从「逐聚落全量」改成「区县汇总（默认） + 点开区县拉聚落明细」—— 两万多聚落不可能平铺，
 * 被一个全国快递商家放大成两万多条「1 商家 / 0 买家」噪声；概览按区县汇总，招商落到具体小区走下钻。
 *
 * <p><b>最要紧的仍是「算不了的」那一格。</b>没坐标的地址推不出聚落、落在所有围栏外的是开城线索、
 * 没标点的门店让自送半径形同虚设 —— 静默丢掉就会把「缺数据」说成「缺需求」。
 *
 * <p>用**合成区县码**（995010 / 995020）隔离区县行，再把坐标放在 **纬度 26.x 带**
 * （别的测试与种子都在 30.x，见 ScopePreviewFlowTest / BindCommunityWithoutPickupTest）——
 * {@code distribution()} 是全库聚合、按坐标把地址归到聚落，买家数会把**落在我围栏里的外来地址**
 * 也数进来。只隔离区县码不够（那次全量红就是栽在这儿）：26.x 这条带没有任何测试/种子用，
 * 外来地址进不了我的围栏，买家数才精确。**改坐标前先确认这条带仍是独占的。**
 */
@SpringBootTest
@ActiveProfiles("test")
class CoverageDistributionTest {

    private static final String D1 = "995010";   // 本测试独占的区县
    private static final String D2 = "995020";   // 下钻隔离用的另一个区县

    @Autowired
    private CommunityAdminService adminService;
    @Autowired
    private CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    private static int seq = 9500;

    private String community(String district, int latE6, int lngE6, int fence) {
        return community(district, "001", latE6, lngE6, fence, CmtCommunity.KIND_ESTATE, null, "OPEN");
    }

    private String community(String district, Integer latE6, Integer lngE6, int fence,
                             String kind, String parentNo, String status) {
        return community(district, "001", latE6, lngE6, fence, kind, parentNo, status);
    }

    /** {@code street} 是区县下的街道后缀；同区县不同街道走不同码,才能真正考验「归并到区县」那一步 */
    private String community(String district, String street, Integer latE6, Integer lngE6, int fence,
                             String kind, String parentNo, String status) {
        var c = new CmtCommunity();
        c.setCommunityNo("DS" + seq++);
        c.setName("分布测试" + seq);
        c.setStatus(status);
        c.setRegionCode(district + street);   // 街道级：区县前缀（6 位）= district
        c.setKind(kind);
        c.setParentNo(parentNo);
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
        if (ids.isEmpty()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> addressMapper.delete(
                Wrappers.<ai.neargo.shop.user.entity.UsrAddress>lambdaQuery()
                        .in(ai.neargo.shop.user.entity.UsrAddress::getAddressId, ids)));
    }

    private void dropCommunities(java.util.List<String> nos) {
        if (nos.isEmpty()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                Wrappers.<CmtCommunity>lambdaQuery().in(CmtCommunity::getCommunityNo, nos)));
    }

    private static DistributionVO.RegionRow region(DistributionVO d, String code) {
        return d.regions().stream().filter(r -> code.equals(r.regionCode())).findFirst().orElse(null);
    }

    private DistributionVO.DistributionRow drillRow(String district, String communityNo) {
        return adminService.communitiesInRegion(district, 1, 500).records().stream()
                .filter(r -> r.communityNo().equals(communityNo)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("★★★ 区县汇总 + 招商清单：有人没商家的聚落进 supplyGaps，区县各桶计数对得上")
    void regionRollupAndSupplyGaps() {
        var nos = new java.util.ArrayList<String>();
        var ids = new java.util.ArrayList<String>();
        try {
            // D1 里两个聚落，**分属不同街道**（001/002，都归并到区县 D1）：A 有买家、B 没买家；
            // 都没有商家覆盖（测试库没有商家框到 D1）。不同街道码是为了真正考验「归并到 6 位区县」那一步
            String a = community(D1, "001", 26_610_000, 120_610_000, 1000,
                    CmtCommunity.KIND_ESTATE, null, "OPEN");
            String b = community(D1, "002", 26_620_000, 120_620_000, 1000,
                    CmtCommunity.KIND_ESTATE, null, "OPEN");
            nos.add(a);
            nos.add(b);
            ids.add(address(26_610_050, 120_610_000));   // 落进 A 的围栏

            var d = adminService.distribution();
            var r = region(d, D1);
            assertThat(r).as("D1 区县行要在概览里").isNotNull();
            assertThat(r.communityCount()).as("D1 两个聚落").isEqualTo(2);
            assertThat(r.buyerCount()).as("D1 买家 1 个").isEqualTo(1);
            assertThat(r.buyerCommunityCount()).as("有买家的聚落 1 个（A）").isEqualTo(1);
            assertThat(r.supplyGapCount()).as("有人没商家 1 个（A）").isEqualTo(1);
            assertThat(r.emptyCount()).as("两头空 1 个（B）").isEqualTo(1);
            assertThat(r.demandGapCount()).as("没有「有商家没人」").isZero();

            assertThat(d.supplyGaps()).extracting(DistributionVO.DistributionRow::communityNo)
                    .as("招商清单恰好是 A（有买家、无商家）").contains(a).doesNotContain(b);
        } finally {
            dropAddresses(ids);
            dropCommunities(nos);
        }
    }

    @Test
    @DisplayName("★★★ 四桶计数自洽：okCount+supply+demand+empty == communities，且 = 各区县之和")
    void totalsReconcile() {
        var nos = new java.util.ArrayList<String>();
        var ids = new java.util.ArrayList<String>();
        try {
            nos.add(community(D1, 26_610_000, 120_610_000, 1000));
            ids.add(address(26_610_050, 120_610_000));
            var d = adminService.distribution();
            var t = d.totals();
            assertThat(t.okCount() + t.supplyGapCount() + t.demandGapCount() + t.emptyCount())
                    .as("四桶之和必须 = 开放聚落数：每个聚落恰好落一桶")
                    .isEqualTo(t.communities());
            // 换个方向数一遍：全局四桶 = 所有区县行对应桶之和（对账量）
            assertThat(d.regions().stream().mapToInt(DistributionVO.RegionRow::supplyGapCount).sum())
                    .isEqualTo(t.supplyGapCount());
            assertThat(d.regions().stream().mapToInt(DistributionVO.RegionRow::demandGapCount).sum())
                    .isEqualTo(t.demandGapCount());
            assertThat(d.regions().stream().mapToInt(DistributionVO.RegionRow::communityCount).sum())
                    .as("区县聚落数之和 = 总聚落数").isEqualTo(t.communities());
            assertThat(t.communities()).as("对照量：至少有我种的这一个，非空").isPositive();
        } finally {
            dropAddresses(ids);
            dropCommunities(nos);
        }
    }

    @Test
    @DisplayName("★★★ 下钻只回这个区县的聚落 —— 别的区县不漏进来")
    void drillStaysInRegion() {
        var nos = new java.util.ArrayList<String>();
        try {
            String inD1 = community(D1, 26_610_000, 120_610_000, 1000);
            String inD2 = community(D2, 26_710_000, 120_710_000, 1000);
            nos.add(inD1);
            nos.add(inD2);
            var rows = adminService.communitiesInRegion(D1, 1, 200).records();
            assertThat(rows).extracting(DistributionVO.DistributionRow::communityNo)
                    .contains(inD1).doesNotContain(inD2);
        } finally {
            dropCommunities(nos);
        }
    }

    @Test
    @DisplayName("★★★ 下钻分页：总数对、每页满、翻页不重不漏 —— 宝安区 6367 个聚落不能一次全发")
    void drillPaginates() {
        var nos = new java.util.ArrayList<String>();
        try {
            // D2 里种 5 个聚落（坐标各不同、都不带买家，分页与买家数无关）
            for (int i = 0; i < 5; i++) {
                nos.add(community(D2, "001", 26_700_000 + i * 2_000, 120_700_000, 300,
                        CmtCommunity.KIND_ESTATE, null, "OPEN"));
            }
            var p1 = adminService.communitiesInRegion(D2, 1, 2);
            assertThat(p1.total()).as("总数 = 这个区县的开放聚落数").isEqualTo(5);
            assertThat(p1.records()).as("第一页满 2 条").hasSize(2);

            var p2 = adminService.communitiesInRegion(D2, 2, 2);
            var p3 = adminService.communitiesInRegion(D2, 3, 2);
            assertThat(p2.records()).hasSize(2);
            assertThat(p3.records()).as("最后一页剩 1 条").hasSize(1);

            var all = new java.util.ArrayList<String>();
            p1.records().forEach(r -> all.add(r.communityNo()));
            p2.records().forEach(r -> all.add(r.communityNo()));
            p3.records().forEach(r -> all.add(r.communityNo()));
            assertThat(all).as("三页拼起来 = 全部 5 个，不重不漏")
                    .containsExactlyInAnyOrderElementsOf(nos);

            assertThat(adminService.communitiesInRegion(D2, 4, 2).records())
                    .as("越过末页返回空，不报错").isEmpty();
        } finally {
            dropCommunities(nos);
        }
    }

    @Test
    @DisplayName("★★★ 「有坐标但不落在任何围栏里」要单列 —— 开城线索，不是「没需求」")
    void addressesOutsideEveryFenceAreCountedSeparately() {
        String near = community(D1, 26_500_000, 120_500_000, 500);
        var nos = new java.util.ArrayList<>(java.util.List.of(near));
        var ids = new java.util.ArrayList<String>();
        try {
            ids.add(address(26_500_100, 120_500_000));   // 圈内
            ids.add(address(27_500_000, 121_500_000));   // 离所有聚落一百多公里
            var d = adminService.distribution();
            assertThat(drillRow(D1, near).buyerCount()).as("圈内那条落到这个聚落上").isEqualTo(1);
            assertThat(d.unattributable().addressesOutsideFences())
                    .as("落在所有围栏之外的那条被静默丢掉了 = 「缺数据」被说成「缺需求」")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            dropAddresses(ids);
            dropCommunities(nos);
        }
    }

    @Test
    @DisplayName("★★★ 没坐标的地址算进「算不了的」，不进买家总数 —— 推不出聚落，不是没人")
    void addressesWithoutCoordsAreNotSilentlyDropped() {
        var ids = new java.util.ArrayList<String>();
        try {
            var before = adminService.distribution();
            int beforeMissing = before.unattributable().addressesWithoutCoords();
            int beforeBuyers = before.totals().buyers();

            ids.add(address(null, null));

            var after = adminService.distribution();
            assertThat(after.unattributable().addressesWithoutCoords())
                    .as("没坐标的地址没被数进缺口 = 分母比真实的小，而这张表看起来很完整")
                    .isEqualTo(beforeMissing + 1);
            assertThat(after.totals().buyers())
                    .as("它不该落到任何聚落 —— 推不出聚落就是推不出，不能挑个最近的塞进去")
                    .isEqualTo(beforeBuyers);
        } finally {
            dropAddresses(ids);
        }
    }

    @Test
    @DisplayName("★★★ 归属走层级优先于距离 —— 楼里的买家不该被算到隔壁小区头上")
    void buyersInsideABuildingCountForTheBuilding() {
        String estate = community(D1, 26_510_000, 120_510_000, 1000);
        String building = community(D1, 26_511_080, 120_510_000, 150,
                CmtCommunity.KIND_BUILDING, estate, "OPEN");   // 离小区中心 ~120 米
        var nos = new java.util.ArrayList<>(java.util.List.of(estate, building));
        var ids = new java.util.ArrayList<String>();
        try {
            // 买家站在小区中心那点：离小区中心 0 米、离楼中心 ~120 米（在楼的 150 围栏内）
            ids.add(address(26_510_000, 120_510_000));
            assertThat(drillRow(D1, building).buyerCount())
                    .as("楼里的买家算到了小区头上 = 分布表与 C 端 resolve 用的不是同一套归属")
                    .isEqualTo(1);
            assertThat(drillRow(D1, estate).buyerCount())
                    .as("对照量：同一个人不能在两行里各算一次").isZero();
        } finally {
            dropAddresses(ids);
            dropCommunities(nos);
        }
    }

    @Test
    @DisplayName("★★ 关掉的聚落不进概览/下钻，但要在「算不了的」里报出条数 —— 历史数据还在")
    void closedCommunitiesAreReportedNotHidden() {
        String open = community(D1, 26_520_000, 120_520_000, 500);
        String closed = community(D1, 26_521_000, 120_520_000, 500,
                CmtCommunity.KIND_ESTATE, null, "CLOSED");
        var nos = new java.util.ArrayList<>(java.util.List.of(open, closed));
        try {
            var d = adminService.distribution();
            assertThat(adminService.communitiesInRegion(D1, 1, 200).records())
                    .extracting(DistributionVO.DistributionRow::communityNo)
                    .contains(open).doesNotContain(closed);
            assertThat(d.unattributable().communitiesClosed()).isGreaterThanOrEqualTo(1);
        } finally {
            dropCommunities(nos);
        }
    }
}
