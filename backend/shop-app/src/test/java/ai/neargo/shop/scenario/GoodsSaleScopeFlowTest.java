package ai.neargo.shop.scenario;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 商品详情页那一行「销售范围」。
 *
 * <p>首页不再说「你在哪儿」之后，买家失去了唯一一处能看出「这件货卖不卖到我这儿」的地方。
 * 这一行把那句话挪到他真正会问的那一刻 —— 点进详情的时候。
 *
 * <p><b>本组真正要守的是那个「空」字。</b> 一条地理范围都没配时，
 * 空的含义由履约路决定：开了快递或自送是「不限」，只做自提是「谁也看不到」。
 * 判反的代价不是报错，是<b>给买家一句正好相反的承诺</b>，页面上看不出任何异常 ——
 * 所以 {@link #pickupOnlyWithNoAreaSaysNothing} 与 {@link #deliveryWithNoAreaIsUnlimited}
 * 要成对读：把 {@code MerchantPortImpl#saleScope} 里那句
 * {@code unlimited = !PICKUP.equals(reach)} 改成恒 true，前者必须立刻变红。
 *
 * <p>另一条是{@link #listDoesNotCarryScope}：列表恒 null。它守的是一条性能约束 ——
 * 把范围填进共用的 {@code toVO} 一行代码就够，而症状（列表页 N+1）
 * 在功能上完全看不出来，没有这条断言没人会发现。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsSaleScopeFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper areaMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String merchant(String reach) {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("销售范围测试-" + reach);
        m.setStatus("ACTIVE");
        m.setFulfillmentReach(reach);
        merchantMapper.insert(m);
        // 真实流程里激活就建好默认店；经营范围门店级之后（V381）范围必须挂在店上
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setEntityNo(m.getEntityNo());
        st.setStoreNo("SST" + m.getEntityNo());
        st.setName("销售范围测试店");
        st.setIsDefault(true);
        st.setStatus("ACTIVE");
        storeMapper.insert(st);
        return m.getEntityNo();
    }

    private void area(String entityNo, String level, String refCode, String status, String mode) {
        var a = new ai.neargo.shop.merchant.entity.MchServiceArea();
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
        a.setEntityNo(entityNo);
        a.setStoreNo("SST" + entityNo);
        a.setLevel(level);
        a.setRefCode(refCode);
        a.setSource("SELF");
        a.setStatus(status);
        a.setMode(mode);
        areaMapper.insert(a);
    }

    /** 造一件在售商品，只为了让详情端点有东西可返回 */
    private String goods(String entityNo) {
        var g = new ai.neargo.shop.product.entity.PrdGoods();
        g.setGoodsNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.GOODS));
        g.setEntityNo(entityNo);
        g.setTitle("销售范围测试商品");
        g.setType("STANDARD");
        g.setOnSale(true);
        goodsMapper.insert(g);
        return g.getGoodsNo();
    }

    private JsonNode detail(String goodsNo) throws Exception {
        return json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    @DisplayName("★★★ 框了两块地方 → 详情里就是那两个名字，且带总数")
    void configuredAreasAreListed() throws Exception {
        String m = merchant("ONSITE");
        // 一条 PENDING、一条 EXCLUDE 一起造：两条都不该出现在买家那一行上
        area(m, "DISTRICT", "440309", "ACTIVE", "INCLUDE");
        area(m, "DISTRICT", "440305", "ACTIVE", "INCLUDE");
        area(m, "DISTRICT", "440303", "PENDING", "INCLUDE");
        area(m, "DISTRICT", "440304", "ACTIVE", "EXCLUDE");

        var scope = detail(goods(m)).get("saleScope");
        assertThat(scope).as("详情要带销售范围").isNotNull();
        assertThat(scope.get("unlimited").asBoolean()).isFalse();
        assertThat(scope.get("areaCount").asInt())
                .as("只数 ACTIVE 的 INCLUDE —— 待审的还没生效，排除项印出来会被读成「这儿也卖」")
                .isEqualTo(2);
        assertThat(scope.get("areaNames")).hasSize(2);
    }

    @Test
    @DisplayName("★★★ 框了范围 + 开了快递 = 列框过的那几块 —— 与可见性同一个判定，快递也尊重框选")
    void expressRespectsConfiguredAreas() throws Exception {
        String m = merchant("SHIPPING");
        area(m, "DISTRICT", "440309", "ACTIVE", "INCLUDE");
        area(m, "DISTRICT", "440305", "ACTIVE", "INCLUDE");

        var scope = detail(goods(m)).get("saleScope");

        /*
         * 判据只有一条：**页面上写的范围 = 买家实际买得到的范围**。
         *
         * 2026-09-29 这条用例断言的是「不限地区」—— 当时可见性开了快递就不看框选。
         * 10-06（#4②）可见性改成「快递也尊重框选」，这一行没跟上：框了两个区又开快递的商家，
         * 详情页写「不限地区」，第三个区的买家其实搜不到这件货。
         * 现在两处调同一个判定（ReachRule），这里跟着可见性走。
         */
        assertThat(scope.get("unlimited").asBoolean())
                .as("框了范围就按框选卖 —— 快递也一样，不能写「不限地区」")
                .isFalse();
        assertThat(scope.get("areaCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 没框范围 + 开了自送 = 不限地区")
    void deliveryWithNoAreaIsUnlimited() {
        var scope = merchantQuery.saleScope(merchant("ONSITE"));
        assertThat(scope.unlimited()).isTrue();
        assertThat(scope.areaNames()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 没框范围 + 只做自提 = 什么都不说（不是「不限」）")
    void pickupOnlyWithNoAreaSaysNothing() {
        var scope = merchantQuery.saleScope(merchant("PICKUP"));
        assertThat(scope.unlimited())
                .as("这个配置的含义是「谁也看不到」；说成不限就是给买家一句正好相反的承诺")
                .isFalse();
        assertThat(scope.isEmpty()).as("端上据此整行不渲染").isTrue();
    }

    @Test
    @DisplayName("★★★ 列表不带销售范围 —— 带上就是一屏几十次范围查询")
    void listDoesNotCarryScope() throws Exception {
        String m = merchant("ONSITE");
        area(m, "DISTRICT", "440309", "ACTIVE", "INCLUDE");
        String goodsNo = goods(m);

        String body = mvc().perform(get("/mp/goods").param("size", "50"))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data").get("records");
        for (JsonNode row : rows) {
            assertThat(row.get("saleScope") == null || row.get("saleScope").isNull())
                    .as("列表里第 %s 行带上了销售范围", row.get("goodsNo")).isTrue();
        }
        // 对照：同一件货走详情端点是有的 —— 否则上面那个循环在「一行都没扫到」时也会绿
        assertThat(detail(goodsNo).get("saleScope")).as("扫描面非空的对照").isNotNull();
    }

    @Autowired
    private ai.neargo.shop.merchant.reach.StoreReachLoader reachLoader;

    @Test
    @DisplayName("★★★ 不限地区 + 排除新疆、西藏 → 详情写「不限地区（新疆、西藏除外）」（TDD-经营范围排除地区 AC4）")
    void unlimitedListsExcludedRegions() throws Exception {
        String m = merchant("SHIPPING");
        area(m, "PROVINCE", "65", "ACTIVE", "EXCLUDE");
        area(m, "PROVINCE", "54", "ACTIVE", "EXCLUDE");
        // 小区级排除不上买家页：「除 3 幢」对外地买家是噪音
        area(m, "COMMUNITY", "CM001", "ACTIVE", "EXCLUDE");

        var scope = detail(goods(m)).get("saleScope");
        assertThat(scope.get("unlimited").asBoolean()).as("只排除不纳入 + 开快递 = 仍是不限").isTrue();
        assertThat(scope.get("excludedNames")).as("两个省级排除，小区级不列").hasSize(2);
    }

    @Test
    @DisplayName("★★★ 另一家店框进了新疆 → 不能对买家说「新疆除外」（主体口径是各店并集）")
    void excludedNotClaimedWhenAnotherStoreCoversIt() {
        String m = merchant("SHIPPING");
        area(m, "PROVINCE", "65", "ACTIVE", "EXCLUDE");
        area(m, "PROVINCE", "54", "ACTIVE", "EXCLUDE");
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setEntityNo(m);
        st.setStoreNo("SST2" + m);
        st.setName("销售范围测试分店");
        st.setIsDefault(false);
        st.setStatus("ACTIVE");
        storeMapper.insert(st);
        var a = new ai.neargo.shop.merchant.entity.MchServiceArea();
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
        a.setEntityNo(m);
        a.setStoreNo(st.getStoreNo());
        a.setLevel("CITY");
        a.setRefCode("6501");   // 乌鲁木齐
        a.setSource("SELF");
        a.setStatus("ACTIVE");
        a.setMode("INCLUDE");
        areaMapper.insert(a);

        var scope = merchantQuery.saleScope(m);
        assertThat(scope.unlimited()).isTrue();
        assertThat(scope.excludedNames())
                .as("分店在乌鲁木齐送货，新疆不能写成「除外」；西藏仍然没人送")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★★ 排除的省：那儿的买家看不到，别处照常（AC2）")
    void unlimitedMinusExcludedProvinceIsInvisibleThere() {
        String m = merchant("SHIPPING");
        area(m, "PROVINCE", "65", "ACTIVE", "EXCLUDE");
        var reach = reachLoader.load(merchantMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, m)), null);
        var urumqi = new ai.neargo.shop.spi.user.CommunityQueryPort.CommunityRef("C-X1", "650102001", null, true);
        var hangzhou = new ai.neargo.shop.spi.user.CommunityQueryPort.CommunityRef("C-X2", "330106001", null, true);
        assertThat(ai.neargo.shop.merchant.reach.ReachRule.covers(reach, urumqi)).as("新疆被排除").isFalse();
        assertThat(ai.neargo.shop.merchant.reach.ReachRule.covers(reach, hangzhou)).as("对照：不限地区照常").isTrue();
    }

    @Test
    @DisplayName("★★ 商家不存在时给空范围，不抛异常 —— 一条脏数据不该让详情页 500")
    void unknownMerchantIsEmptyNotError() {
        var scope = merchantQuery.saleScope("M_NOT_EXISTS");
        assertThat(scope.isEmpty()).isTrue();
    }

    /** 收尾：本组造的商家与范围行都带自己的前缀，不动共享种子 */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        var mine = merchantMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .likeRight(ai.neargo.shop.merchant.entity.MchEntity::getName, "销售范围测试-"));
        for (var m : mine) {
            areaMapper.delete(Wrappers.<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                    .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m.getEntityNo()));
            goodsMapper.delete(Wrappers.<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdGoods::getEntityNo, m.getEntityNo()));
            storeMapper.delete(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                    .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, m.getEntityNo()));
            merchantMapper.deleteById(m.getId());
        }
    }
}
