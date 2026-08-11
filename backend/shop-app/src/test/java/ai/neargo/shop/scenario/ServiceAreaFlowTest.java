package ai.neargo.shop.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经营范围新模型：履约能力 × 地理覆盖（ADR-013 阶段二）。
 *
 * <p><b>这组用例守的是「可见性没变」</b>，不是「新功能能用」。
 * 换的是 C 端可见性的唯一出口 {@code reachableCommunities}，改错的症状是
 * 「商品谁也搜不到」且不报错 —— 没有对照的话，这种故障要等商家来问才会被发现。
 *
 * <p>所以每条用例都写成「原来的某一档 → 现在应当得到同一批社区」。
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceAreaFlowTest {

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;

    @Autowired
    private ai.neargo.shop.spi.user.CommunityQueryPort communityQuery;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper areaMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    /** 社区号自增：BizKey 没有社区这一档，测试里自己造就够 */
    private static int seq = 9000;

    /** 造一个商家，只设履约能力，不给覆盖项 */
    private String merchant(String reach) {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("范围测试-" + reach);
        m.setStatus("ACTIVE");
        m.setFulfillmentReach(reach);
        merchantMapper.insert(m);
        return m.getEntityNo();
    }

    private void area(String entityNo, String level, String refCode) {
        var a = new ai.neargo.shop.merchant.entity.MchServiceArea();
        a.setEntityNo(entityNo);
        a.setLevel(level);
        a.setRefCode(refCode);
        a.setSource("SELF");
        a.setStatus("ACTIVE");
        areaMapper.insert(a);
    }

    /** 造一个挂在指定区划下的开放社区 */
    private String community(String regionCode) {
        var c = new ai.neargo.shop.community.entity.CmtCommunity();
        c.setCommunityNo("CT" + seq++);
        c.setName("区划测试小区-" + regionCode);
        c.setStatus("OPEN");
        c.setRegionCode(regionCode);
        c.setFenceRadius(1000);
        communityMapper.insert(c);
        return c.getCommunityNo();
    }

    // ---------------------------------------------------------------- 行为保持

    @Test
    @DisplayName("★ 原 COMMUNITY → PICKUP + 社区覆盖项：还是那几个社区")
    void pickupWithCommunityAreasKeepsSameSet() {
        String m = merchant("PICKUP");
        String c1 = community("330106002");
        String c2 = community("330106003");
        area(m, "COMMUNITY", c1);
        area(m, "COMMUNITY", c2);

        assertThat(merchantQuery.reachableCommunities(m)).containsExactlyInAnyOrder(c1, c2);
    }

    @Test
    @DisplayName("★ 原 COMMUNITY 却没配社区 → 仍然谁也看不到（自提必须有落点）")
    void pickupWithoutAreasIsInvisible() {
        String m = merchant("PICKUP");
        /*
         * 反过来做会出事：把 PICKUP 的空当成「不限」，
         * 一家没配社区的菜摊会突然铺满全平台 —— 而且不报错。
         */
        assertThat(merchantQuery.reachableCommunities(m)).isEmpty();
    }

    @Test
    @DisplayName("★ 原 CITY → ONSITE 且没有覆盖项：仍然是全部开放社区")
    void onsiteWithoutAreasStaysUnlimited() {
        String m = merchant("ONSITE");
        /*
         * 这一条是迁移里最危险的一格：存量 CITY 商家的 service_city_code 全是 NULL，
         * 造不出覆盖项。把「无覆盖项」当成「谁也看不到」的话，
         * 他们会在迁移当天集体从 C 端消失，而且不报错。
         */
        assertThat(merchantQuery.reachableCommunities(m))
                .containsExactlyInAnyOrderElementsOf(communityQuery.openCommunityNos());
    }

    @Test
    @DisplayName("★ 原 PLATFORM → SHIPPING：全部开放社区，且不必逐个勾")
    void shippingIgnoresAreas() {
        String m = merchant("SHIPPING");
        // 就算勾了一个社区也不收窄 —— 快递没有履约半径，
        // 逐个勾的话新开城的社区永远进不了这份手工清单
        area(m, "COMMUNITY", community("330106002"));

        assertThat(merchantQuery.reachableCommunities(m))
                .containsExactlyInAnyOrderElementsOf(communityQuery.openCommunityNos());
    }

    // ---------------------------------------------------------------- 新能力

    @Test
    @DisplayName("★ 按区县覆盖：前缀展开，挂到区与挂到街道的社区都命中")
    void districtAreaExpandsByPrefix() {
        String m = merchant("ONSITE");
        String onDistrict = community("330106");      // 直接挂在区上
        String onStreet = community("330106002");     // 挂在该区下的街道
        String elsewhere = community("330105001");    // 隔壁区
        area(m, "DISTRICT", "330106");

        var reachable = merchantQuery.reachableCommunities(m);
        assertThat(reachable).contains(onDistrict, onStreet);
        assertThat(reachable).doesNotContain(elsewhere);
    }

    @Test
    @DisplayName("★ 跨粒度组合：三个小区 + 一个区 —— 这正是三档枚举做不到的事")
    void mixedGranularityIsTheWholePoint() {
        String m = merchant("ONSITE");
        String far = community("330100999");           // 不在西湖区下，靠逐个点名
        String inDistrict = community("330106004");
        area(m, "COMMUNITY", far);
        area(m, "DISTRICT", "330106");

        assertThat(merchantQuery.reachableCommunities(m)).contains(far, inDistrict);
    }

    @Test
    @DisplayName("★ 关城的社区不会因为「商家框了这个区」而重新可见")
    void closedCommunityStaysHidden() {
        String m = merchant("ONSITE");
        String open = community("330199001");
        var closed = new ai.neargo.shop.community.entity.CmtCommunity();
        closed.setCommunityNo("CT" + seq++);
        closed.setName("已关城");
        closed.setStatus("CLOSED");
        closed.setRegionCode("330199002");
        closed.setFenceRadius(1000);
        communityMapper.insert(closed);

        area(m, "DISTRICT", "330199");
        var reachable = merchantQuery.reachableCommunities(m);
        assertThat(reachable).contains(open);
        assertThat(reachable).doesNotContain(closed.getCommunityNo());
    }

    @Test
    @DisplayName("★ 待审的覆盖项不生效 —— 勾了整个区不等于当场就覆盖整个区")
    void pendingAreaDoesNotCount() {
        String m = merchant("PICKUP");
        String c = community("330106002");
        var a = new ai.neargo.shop.merchant.entity.MchServiceArea();
        a.setEntityNo(m);
        a.setLevel("COMMUNITY");
        a.setRefCode(c);
        a.setSource("SELF");
        a.setStatus("PENDING");
        areaMapper.insert(a);

        // PENDING 不算覆盖项，于是这家 PICKUP 商家等同「没框」→ 谁也看不到
        assertThat(merchantQuery.reachableCommunities(m)).isEmpty();
    }

    @Test
    @DisplayName("★ 空区划码不匹配一切 —— 那是最危险的默认值")
    void blankRegionMatchesNothing() {
        assertThat(communityQuery.openCommunityNosUnderRegion("")).isEmpty();
        assertThat(communityQuery.openCommunityNosUnderRegion(null)).isEmpty();
    }

    @Test
    @DisplayName("★ 覆盖项走物理删除 —— 移除后再加回同一条不撞唯一键")
    void areaRemoveThenAddAgain() {
        String m = merchant("PICKUP");
        String c = community("330106002");
        area(m, "COMMUNITY", c);

        /*
         * 逻辑删 + 业务唯一键这个组合在本仓库踩过四次（门店角色、商品社区池、
         * 商家社区表各修了一个 revive）。这张表从一开始就走物理删除，
         * 从根上消掉那类 bug —— 这条用例守的就是「别哪天改回逻辑删」。
         */
        areaMapper.hardDelete(m, "COMMUNITY", c);
        assertThat(merchantQuery.reachableCommunities(m)).isEmpty();

        area(m, "COMMUNITY", c);
        assertThat(merchantQuery.reachableCommunities(m)).containsExactly(c);
    }

    // ---------------------------------------------------------------- B 端写入

    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.merchant.service.MerchantStoreService storeService;

    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    /**
     * 建一行门店。真实流程里这一行由**入驻审核通过**时创建，
     * 门店保存只会更新它 —— 测试里要照着这个前提造，否则测的是一条不存在的路径。
     */
    private void store(String merchantNo) {
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("ST" + seq++);
        st.setEntityNo(merchantNo);
        st.setIsDefault(true);
        storeMapper.insert(st);
    }

    @Test
    @DisplayName("★ 商家保存「一个社区 + 一个区」—— 三档枚举做不到的组合，从端上真的能存进来")
    void merchantSavesMixedAreas() {
        String m = merchant("ONSITE");
        store(m);
        String c = community("330106002");

        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE",
                java.util.List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("DISTRICT", "330106"))));

        var vo = storeService.profile(m);
        assertThat(vo.fulfillmentReach()).isEqualTo("ONSITE");
        assertThat(vo.serviceAreas()).hasSize(2);
        // 名字由后端补：端上只拿到 330106 的话要么显示数字要么再查一次
        assertThat(vo.serviceAreas().stream()
                .map(ai.neargo.shop.merchant.dto.StoreProfileVO.ServiceAreaVO::name))
                .anySatisfy(n -> assertThat(n).contains("区划测试小区"));
    }

    @Test
    @DisplayName("★ 勾社区自助生效，勾区要审 —— 影响面差一个量级")
    void districtAreaNeedsReview() {
        String m = merchant("ONSITE");
        store(m);
        String c = community("330106002");
        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE",
                java.util.List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("DISTRICT", "330106"))));

        var rows = areaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m));
        assertThat(rows).anySatisfy(r -> {
            if ("COMMUNITY".equals(r.getLevel())) {
                assertThat(r.getStatus()).isEqualTo("ACTIVE");
            }
        });
        assertThat(rows).anySatisfy(r -> {
            if ("DISTRICT".equals(r.getLevel())) {
                // 一家菜摊声称覆盖整个西湖区，得有履约能力佐证 —— 待审期间不参与展开
                assertThat(r.getStatus()).isEqualTo("PENDING");
            }
        });
    }

    @Test
    @DisplayName("★ 覆盖项传 null = 这次不改（老版本 b-app 不传），传空列表才是清空")
    void nullAreasMeansUnchanged() {
        String m = merchant("ONSITE");
        store(m);
        String c = community("330106002");
        area(m, "COMMUNITY", c);

        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE", null));

        // null 不该把已有覆盖项抹掉 —— 抹掉的话老版本端一保存公告，
        // 这家店的范围就没了，而他只是改了句公告
        assertThat(merchantQuery.reachableCommunities(m)).containsExactly(c);
    }
}
