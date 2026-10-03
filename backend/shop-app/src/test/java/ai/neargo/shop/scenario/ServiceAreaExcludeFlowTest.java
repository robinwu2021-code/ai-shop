package ai.neargo.shop.scenario;

import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 楼栋覆盖与 EXCLUDE（V321 / T7）。
 *
 * <p>改的是 C 端可见性的**唯一出口** {@code reachableCommunities} ——
 * 上架写社区池、商家详情可达性、履约都只认它。改错的症状不是报错，
 * 是某个商家的货悄悄从一批买家的首页上消失，而他自己查不出来。
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceAreaExcludeFlowTest {

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;
    @Autowired
    private ai.neargo.shop.merchant.service.MerchantStoreService storeService;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper areaMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    private static int seq = 7100;

    private String merchant(String reach) {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("排除测试-" + reach);
        m.setStatus("ACTIVE");
        m.setFulfillmentReach(reach);
        merchantMapper.insert(m);
        // 默认门店：save() 走的是「改门面」那条路，没有店就插不进去
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("EXST" + seq++);
        st.setEntityNo(m.getEntityNo());
        st.setIsDefault(true);
        storeMapper.insert(st);
        return m.getEntityNo();
    }

    /** @param parentNo 非空即楼栋（kind=BUILDING），挂在那个小区/园区下 */
    private String community(String regionCode, String parentNo) {
        var c = new CmtCommunity();
        c.setCommunityNo("EX" + seq++);
        c.setName((parentNo == null ? "排除测试小区-" : "排除测试楼栋-") + seq);
        c.setStatus("OPEN");
        c.setRegionCode(regionCode);
        c.setFenceRadius(parentNo == null ? 1000 : 150);
        c.setKind(parentNo == null ? CmtCommunity.KIND_ESTATE : CmtCommunity.KIND_BUILDING);
        c.setParentNo(parentNo);
        communityMapper.insert(c);
        return c.getCommunityNo();
    }

    private void area(String entityNo, String level, String refCode, String mode) {
        var a = new MchServiceArea();
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
        a.setEntityNo(entityNo);
        a.setLevel(level);
        a.setRefCode(refCode);
        a.setSource("SELF");
        a.setStatus("ACTIVE");
        a.setMode(mode);
        areaMapper.insert(a);
    }

    @Test
    @DisplayName("★★★ 框了小区 = **自动覆盖里面每一栋楼**")
    void includingAnEstateCoversItsBuildings() {
        String estate = community("330106900", null);
        String b1 = community("330106900", estate);
        String b2 = community("330106900", estate);
        String m = merchant("PICKUP");
        area(m, "COMMUNITY", estate, "INCLUDE");

        assertThat(merchantQuery.reachableCommunities(m))
                .as("框了小区却盖不到里面的楼 = 商家要逐栋勾，而那不现实")
                .contains(estate, b1, b2);
    }

    @Test
    @DisplayName("★★★ 框小区 + 排除其中一栋：那一栋看不到，别的楼照旧")
    void excludeOneBuildingKeepsTheRest() {
        String estate = community("330106901", null);
        String keep = community("330106901", estate);
        String drop = community("330106901", estate);
        String m = merchant("PICKUP");
        area(m, "COMMUNITY", estate, "INCLUDE");
        area(m, "COMMUNITY", drop, "EXCLUDE");

        var reach = merchantQuery.reachableCommunities(m);
        assertThat(reach).contains(estate, keep);
        assertThat(reach)
                .as("排除了却还在 = B 端显示「已排除」而 C 端照样看得到，商家会以为功能坏了")
                .doesNotContain(drop);
    }

    @Test
    @DisplayName("★★★ 排除一个园区 = **连它的全部楼栋一起排除**（展开函数只有一份）")
    void excludingAnEstateAlsoExcludesItsBuildings() {
        String street = "330106902";
        String keep = community(street, null);
        String dropEstate = community(street, null);
        String dropBuilding = community(street, dropEstate);
        String m = merchant("PICKUP");
        area(m, "STREET", street, "INCLUDE");
        area(m, "COMMUNITY", dropEstate, "EXCLUDE");

        var reach = merchantQuery.reachableCommunities(m);
        assertThat(reach).contains(keep);
        assertThat(reach)
                .as("排除展开与纳入展开写成两套的话，有一天它们会不一样，而没有测试会发现")
                .doesNotContain(dropEstate, dropBuilding);
    }

    @Test
    @DisplayName("★★★ 自送商家**只有 EXCLUDE**：结果是「全部 − 排除」，不是空")
    void deliveryWithOnlyExcludeStillCoversTheRest() {
        /*
         * **这是全组最容易写反的一条。**
         *
         * 「我上门送、不限范围，但不送这个小区」——判据若沿用 areas.isEmpty()，
         * 他会因为「有 area 行」而跳过「没框=不限」那个 fallback、展开出空集，
         * 于是**谁也看不到**。结果正好相反，而且不报错：
         * 商家只会发现自己的货从所有人的首页上消失了。
         */
        String dropped = community("330106903", null);
        String m = merchant("ONSITE");
        area(m, "COMMUNITY", dropped, "EXCLUDE");

        var reach = merchantQuery.reachableCommunities(m);
        assertThat(reach)
                .as("只写了排除就变成谁也看不到 = 判据用了 areas 而不是 includes")
                .isNotEmpty();
        assertThat(reach).doesNotContain(dropped);
    }

    @Test
    @DisplayName("★★ 结果去重 —— 同时框小区与其中一栋楼，那一栋只出现一次")
    // 后果**不在池里**（池写入自己会吞掉重复，实测消融不变红），
    // 在所有直接拿这个集合当清单用的地方：自提点候选会重复列同一个点，
    // 「覆盖了几个聚落」这类计数虚高，上架时同一个聚落还要多写一遍。
    void overlappingIncludesAreDeduped() {
        String estate = community("330106904", null);
        String b = community("330106904", estate);
        String m = merchant("PICKUP");
        area(m, "COMMUNITY", estate, "INCLUDE");
        area(m, "COMMUNITY", b, "INCLUDE");

        var reach = merchantQuery.reachableCommunities(m);
        assertThat(reach.stream().filter(b::equals).count())
                .as("不去重 = 自提点候选里同一个点列两遍，覆盖数也跟着虚高")
                .isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────
    // 写入端。**读得出来只是一半** —— 这一列要有东西写它，
    // 而保存是「全量删重插」，最容易出的事不是写错，是把上次写的悄悄吃掉。
    // ────────────────────────────────────────────────────────────────

    private void save(String m, ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand... areas) {
        storeService.save(m, null, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                null, null, "08:00-20:00", "文一西路 1 号", null,
                java.util.List.of(), null, null, null, "PICKUP",
                java.util.List.of(areas), null, null));
    }

    private static ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand cmd(
            String level, String ref, String mode) {
        return new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand(level, ref, mode);
    }

    @Test
    @DisplayName("★★★ 排除项存得下、回显得出来 —— 否则 B 端只能把它当纳入项传回来")
    void excludeRoundTrips() {
        String estate = community("330106905", null);
        String drop = community("330106905", estate);
        String m = merchant("PICKUP");
        save(m, cmd("COMMUNITY", estate, "INCLUDE"), cmd("COMMUNITY", drop, "EXCLUDE"));

        var areas = storeService.profile(m).serviceAreas();
        assertThat(areas).extracting("refCode", "mode")
                .as("回显丢了 mode = 商家下一次保存就把排除项改成了纳入项")
                .contains(org.assertj.core.groups.Tuple.tuple(estate, "INCLUDE"),
                        org.assertj.core.groups.Tuple.tuple(drop, "EXCLUDE"));
        assertThat(merchantQuery.reachableCommunities(m)).doesNotContain(drop);
    }

    @Test
    @DisplayName("★★★ 保存全量删重插：**端上原样回传就要原样留住**")
    void resavingKeepsExcludes() {
        /*
         * 这是删重插那个写法的真实代价。商家改一下营业时间、把上次那两条范围
         * 原样传回来 —— 只要 mode 在链路上任何一环丢掉，排除项就会被重插成纳入项：
         * 保存成功、界面上范围也没少一条，货却悄悄回到了他明确排除掉的那栋楼里。
         */
        String estate = community("330106906", null);
        String drop = community("330106906", estate);
        String m = merchant("PICKUP");
        save(m, cmd("COMMUNITY", estate, "INCLUDE"), cmd("COMMUNITY", drop, "EXCLUDE"));

        var back = storeService.profile(m).serviceAreas().stream()
                .map(a -> cmd(a.level(), a.refCode(), a.mode()))
                .toArray(ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand[]::new);
        save(m, back);

        assertThat(merchantQuery.reachableCommunities(m))
                .as("回传一次就失效 = mode 在回显或写入端断了一环")
                .doesNotContain(drop);
    }

    @Test
    @DisplayName("★★★ 父子归一**只在同一个 mode 内**：框区 + 排除区里的街道，排除不能被当成冗余子项丢掉")
    void normalizeDoesNotSwallowExcludesUnderAnIncludedParent() {
        /*
         * 「框西湖区、排除文新街道」正是这个功能存在的理由，而排除项的国标码
         * 天然以纳入项为前缀 —— 跨 mode 归一会把它认成「省已经盖住它了」那类冗余项，
         * 于是商家勾了排除、保存成功、范围里也确实还在，货照送。
         */
        String district = "330107";
        String street = "330107003";
        String keep = community("330107001", null);
        String dropped = community(street, null);
        String m = merchant("PICKUP");
        save(m, cmd("DISTRICT", district, "INCLUDE"), cmd("STREET", street, "EXCLUDE"));

        var reach = merchantQuery.reachableCommunities(m);
        assertThat(reach).contains(keep);
        assertThat(reach)
                .as("归一跨了 mode = 排除项在落库前就没了，判定端再对也没用")
                .doesNotContain(dropped);
    }
}
