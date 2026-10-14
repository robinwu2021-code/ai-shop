package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import ai.neargo.shop.support.TestStoreCategory;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 子单按门店拆（ADR-031，TDD-下单按门店拆单与运费模板 AC4/AC11）。
 *
 * <p>2026-10-09 线上：同一主体「虹选」下鲜果店卖柿子、粮油店卖盐。一单买两样时，
 * 按主体分组只能落一家店 —— 总有一件被落到不卖它的店，预览 70076。
 * 现在一家门店一张子单：两件各由各的店卖、各由各的店发，主体照旧是同一个（结算按它）。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreSplitOrderFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    @Autowired
    private SubOrderMapper subOrderMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 同主体两家店的货进一单：预览两组、下单两张子单，各带各的店")
    void sameEntityTwoStoresTwoSubOrders() throws Exception {
        String biz = merchant("12600340010", "按店拆单·主体");
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "按店拆单·分店");
        String inA = listedGoods(biz, storeA, "按店拆单·总店的货");
        String inB = listedGoods(biz, storeB, "按店拆单·分店的货");

        String buyer = TestLogin.consumer(mvc(), json, otpStore, "13000340010");
        cartAdd(buyer, inA);
        cartAdd(buyer, inB);

        JsonNode preview = data(mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString());
        JsonNode children = preview.get("subOrders");
        assertThat(children.size()).as("同主体两家店 = 两组，按主体会并成一组").isEqualTo(2);
        Set<String> previewStores = new HashSet<>();
        children.forEach(c -> previewStores.add(c.get("store").get("storeNo").asString()));
        assertThat(previewStores).containsExactlyInAnyOrder(storeA, storeB);

        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "split-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String orderNo = data(body).get("orderNo").asString();

        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getOrderNo, orderNo)));
        assertThat(subs).hasSize(2);
        assertThat(subs.stream().map(OrdSubOrder::getStoreNo).toList())
                .as("两件各由各的店发 —— 落到同一家店就是线上那个 70076")
                .containsExactlyInAnyOrder(storeA, storeB);
        assertThat(subs.stream().map(OrdSubOrder::getEntityNo).distinct().toList())
                .as("主体不变：结算、分账仍按它").hasSize(1);
        assertThat(subs.stream().map(OrdSubOrder::getSubOrderNo).distinct().count())
                .as("子单号按主体发会撞 uk_sub_order_no，整单失败").isEqualTo(2);
    }

    // ---------------------------------------------------------------- 装配

    private JsonNode data(String body) {
        return json.readTree(body).get("data");
    }

    private void cartAdd(String buyer, String goodsNo) throws Exception {
        String skuNo = data(mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse()
                .getContentAsString()).get("skus").get(0).get("skuNo").asString();
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 在指定门店下建一件过审、在架的货 */
    private String listedGoods(String token, String storeNo, String title) throws Exception {
        TestStoreCategory.open(mvc(), json, token, storeNo, "CAT210");
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":50}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = data(body).get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }

    private String defaultStoreNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return data(body).get(0).get("storeNo").asString();
    }

    private String createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 9 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return data(body).get("storeNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = data(body).get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
