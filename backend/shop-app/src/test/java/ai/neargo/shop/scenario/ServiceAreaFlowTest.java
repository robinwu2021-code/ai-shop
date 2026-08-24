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
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
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
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
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

    /** 再开一家非默认店，返回它的门店号 */
    private String extraStore(String merchantNo, String name) {
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("ST" + seq++);
        st.setEntityNo(merchantNo);
        st.setName(name);
        st.setIsDefault(false);
        storeMapper.insert(st);
        return st.getStoreNo();
    }

    @Test
    @DisplayName("★★ 公告过期即空 —— 「昨天到货」不该挂到今天，而且两条读路径要一致")
    void announcementExpires() {
        String m = merchant("PICKUP");
        store(m);
        String c = community("330106002");
        var areas = java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                .AreaCommand("COMMUNITY", c));

        long past = System.currentTimeMillis() - 3600_000L;
        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "今天到了新米", past, "08:00-20:00", "文一西路 1 号", null,
                java.util.List.of(), null, null, null, "PICKUP", areas, null, null));

        /*
         * **B 端与 C 端必须给出同一个答案**。只在一处判过期的话，
         * 商家自己看是空的、买家看到的却是昨天的货 —— 而这种不一致
         * 没有任何报错，只有买家白跑一趟才会暴露。
         */
        assertThat(storeService.profile(m).announcement()).as("B 端").isEmpty();
        assertThat(merchantQuery.storeFront(m).orElseThrow().announcement()).as("C 端").isEmpty();

        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "今天到了新米", System.currentTimeMillis() + 3600_000L, "08:00-20:00", "文一西路 1 号", null,
                java.util.List.of(), null, null, null, "PICKUP", areas, null, null));
        assertThat(storeService.profile(m).announcement()).isEqualTo("今天到了新米");
    }

    @Test
    @DisplayName("★★ 只改公告不碰门面 —— 高频与低频拆成两条路，改一句话不该重写地址")
    void saveAnnouncementLeavesTheRestAlone() {
        String m = merchant("PICKUP");
        store(m);
        String c = community("330106002");
        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "旧公告", null, "06:30-21:00", "龙澜大道 441 号", "3 栋 501",
                java.util.List.of(), null, null, null, "PICKUP",
                java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                        .AreaCommand("COMMUNITY", c)), 22_695_293, 114_027_370));

        storeService.saveAnnouncement(m, null, "今天到了新米", null);

        var vo = storeService.profile(m);
        assertThat(vo.announcement()).isEqualTo("今天到了新米");
        /*
         * 这几条是这个口子存在的**全部理由**：混在一份全量保存里时，端上少传一个字段
         * 就会把它写回空 —— 而「地址没了」这种事商家要等到有人来取货才发现。
         */
        assertThat(vo.openHours()).isEqualTo("06:30-21:00");
        assertThat(vo.address()).isEqualTo("龙澜大道 441 号");
        assertThat(vo.addressDetail()).isEqualTo("3 栋 501");
        assertThat(vo.latE6()).isEqualTo(22_695_293);
        assertThat(vo.serviceAreas()).hasSize(1);
    }

    @Test
    @DisplayName("★ 常用公告：最近用过的排最前、不重复、最多 5 条")
    void recentAnnouncementsAreDeduped() {
        String m = merchant("PICKUP");
        store(m);
        String c = community("330106002");
        var areas = java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                .AreaCommand("COMMUNITY", c));

        for (String text : java.util.List.of("一", "二", "三", "一", "四", "五", "六")) {
            storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                    text, null, "08:00-20:00", "文一西路 1 号", null,
                    java.util.List.of(), null, null, null, "PICKUP", areas, null, null));
        }

        var recent = storeService.profile(m).announcementRecent();
        assertThat(recent).hasSize(5);
        assertThat(recent.get(0)).as("刚用过的排最前").isEqualTo("六");
        assertThat(recent).as("同一句只留一条").containsExactly("六", "五", "四", "一", "三");
    }

    @Test
    @DisplayName("★★ 重选地图不冲掉门牌号 —— 两截分开存，老版本端上不传时也不许被抹掉")
    void addressDetailSurvivesRepick() {
        String m = merchant("PICKUP");
        store(m);
        String c = community("330106002");
        var areas = java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                .AreaCommand("COMMUNITY", c));

        // 先选点 + 填门牌号
        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "龙澜大道 441 号招商锦绣观园", "3 栋 2 单元 501",
                java.util.List.of(), null, null, null, "PICKUP", areas, 22_695_293, 114_027_370));
        assertThat(storeService.profile(m).addressDetail()).isEqualTo("3 栋 2 单元 501");

        /*
         * 再点一次地图选点：端上只重写 address。**门牌号必须留着** ——
         * 合成一格的年代，这一步会把商家补的那截无声抹掉，而地址看着还是对的，
         * 只是又回到了小区门口。
         */
        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "龙澜大道 441 号招商锦绣观园东门", null,
                java.util.List.of(), null, null, null, "PICKUP", areas, 22_695_300, 114_027_380));
        assertThat(storeService.profile(m).addressDetail())
                .as("null = 这次不改门牌号（老版本端上就不传这个字段）").isEqualTo("3 栋 2 单元 501");

        // 空串才是「清掉」
        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "龙澜大道 441 号招商锦绣观园东门", "",
                java.util.List.of(), null, null, null, "PICKUP", areas, 22_695_300, 114_027_380));
        assertThat(storeService.profile(m).addressDetail()).isEmpty();
    }

    @Test
    @DisplayName("★★ 门面资料是**门店级**的：地址填在哪家店，就只从哪家店读回来")
    void profileFollowsCurrentStore() {
        String m = merchant("PICKUP");
        store(m);                                   // 默认店，没地址
        String second = extraStore(m, "第二家店");
        String c = community("330106002");

        var cmd = new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "龙澜大道 441 号", java.util.List.of(),
                null, null, null, "PICKUP",
                java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                        .AreaCommand("COMMUNITY", c)));
        storeService.save(m, second, cmd);

        /*
         * 线上就是这个形状：M0001 三家店，地址填在第二家，而 profile 用
         * `limit 1`（不排序、不看默认店）读到第一家 —— 于是「门店自取」一直提示
         * 「还没填地址」，商家反复去填也没用，他填的和系统读的不是同一行。
         */
        assertThat(storeService.profile(m, second).address()).isEqualTo("龙澜大道 441 号");
        assertThat(storeService.profile(m, null).address())
                .as("默认店没填过地址，不该把别家的读过来").isEmpty();
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
    @DisplayName("★★ 按省覆盖：省码是 2 位前缀，整省的社区都命中，勾完当场生效（2026-08-24 起不再要审）")
    void provinceAreaExpandsImmediately() {
        String m = merchant("SHIPPING");
        store(m);
        String inProvince = community("330106002");   // 浙江 → 杭州 → 西湖 → 街道
        String otherProvince = community("140802001"); // 山西 → 运城

        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE",
                java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                        .AreaCommand("PROVINCE", "33"))));

        var rows = areaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m));
        /*
         * 省这一档是新加的（AREA_LEVEL.PROVINCE）。后端没有为它写过任何分支 ——
         * 这条用例守的正是「不写分支也对」：展开归入「国标码前缀」，
         * 状态一律 ACTIVE，不再走待审队列。
         */
        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo("ACTIVE"));

        // 保存当场就展开，而且只展开这个省
        var reachable = merchantQuery.reachableCommunities(m);
        assertThat(reachable).contains(inProvince);
        assertThat(reachable).doesNotContain(otherProvince);
    }

    @Test
    @DisplayName("★★ 同时勾了省与省下面的区 → 只留省。留着子项会在运营队列里多一条永远没意义的待审")
    void parentSwallowsChild() {
        String m = merchant("ONSITE");
        store(m);
        String c = community("330106002");

        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE",
                java.util.List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("PROVINCE", "33"),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("CITY", "3301"),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("DISTRICT", "330106"),
                        // 聚落不参与归一：它的归属挂在 cmt_community.region_code 上，这一层看不见
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c))));

        var rows = areaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m));
        assertThat(rows).extracting(ai.neargo.shop.merchant.entity.MchServiceArea::getLevel)
                .containsExactlyInAnyOrder("PROVINCE", "COMMUNITY");
    }

    @Test
    @DisplayName("★ 勾社区、勾区，保存后都是同一句话：当场生效")
    void districtAreaIsSelfEffectiveToo() {
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
        assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo("ACTIVE"));
    }

    @Test
    @DisplayName("★ 已经生效的覆盖不会因为商家改了句公告就被打回")
    void approvedAreaSurvivesLaterSave() {
        String m = merchant("ONSITE");
        store(m);
        var cmd = new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE",
                java.util.List.of(new ai.neargo.shop.merchant.service.MerchantStoreService
                        .AreaCommand("DISTRICT", "330106")));
        storeService.save(m, cmd);

        // 商家回来只改了一句公告，覆盖项原样再传一遍
        storeService.save(m, cmd);

        var after = areaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m));
        assertThat(after).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo("ACTIVE"));
    }

    @Test
    @DisplayName("★ 状态必须回显给端上 —— 商家看清单里的每一条是不是真已经生效")
    void statusIsReturnedToClient() {
        String m = merchant("ONSITE");
        store(m);
        String c = community("330106002");
        var profile = storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                "营业中", "08:00-20:00", "文一西路 1 号", java.util.List.of(),
                null, null, null, "ONSITE",
                java.util.List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("DISTRICT", "330106"))));

        assertThat(profile.serviceAreas())
                .allSatisfy(a -> assertThat(a.status()).isEqualTo("ACTIVE"));
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
