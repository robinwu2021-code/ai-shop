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
        return m.getEntityNo();
    }

    private void area(String entityNo, String level, String refCode, String status, String mode) {
        var a = new ai.neargo.shop.merchant.entity.MchServiceArea();
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
        a.setEntityNo(entityNo);
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
            merchantMapper.deleteById(m.getId());
        }
    }
}
