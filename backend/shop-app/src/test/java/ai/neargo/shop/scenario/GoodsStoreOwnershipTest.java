package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import ai.neargo.shop.support.TestStoreCategory;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品归属门店（ADR-030 / ADR-031，TDD-下单按门店拆单与运费模板 AC1）。
 *
 * <p>商品只属于一家门店：B 端在哪家店下建的就归哪家（{@code X-Store-No}，
 * 不带时是默认店）；编辑不改归属 —— 多店卖同款是每家店各建一件，不是把一件挪来挪去。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsStoreOwnershipTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 在分店下建的商品归分店；不带门店建的归默认店")
    void newGoodsBelongsToCurrentStore() throws Exception {
        String biz = merchant("12600330010", "归属门店·总店");
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "归属门店·分店");
        TestStoreCategory.open(mvc(), json, biz, storeA, "CAT210");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        String inB = save(biz, storeB, null);
        String inDefault = save(biz, null, null);

        assertThat(storeOf(inB)).as("在分店下建的商品归分店").isEqualTo(storeB);
        assertThat(storeOf(inDefault)).as("不带 X-Store-No 时是默认店").isEqualTo(storeA);
    }

    @Test
    @DisplayName("★ 编辑不改归属 —— 换一家店的上下文保存，商品还属于原来那家")
    void editKeepsOwner() throws Exception {
        String biz = merchant("12600330020", "归属不变·总店");
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "归属不变·分店");
        TestStoreCategory.open(mvc(), json, biz, storeA, "CAT210");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        String goodsNo = save(biz, storeB, null);
        save(biz, storeA, goodsNo);

        assertThat(storeOf(goodsNo)).isEqualTo(storeB);
    }

    // ---------------------------------------------------------------- 装配

    private String storeOf(String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getGoodsNo, goodsNo)));
        return g.getStoreNo();
    }

    private String save(String token, String storeNo, String goodsNo) throws Exception {
        var req = post("/biz/goods/save").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + (goodsNo == null ? "" : "\"goodsNo\":\"" + goodsNo + "\",")
                        + "\"categoryNo\":\"CAT210\",\"title\":\"归属测试品\",\"type\":\"NORMAL\","
                        + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}");
        if (storeNo != null) {
            req = req.header("X-Store-No", storeNo);
        }
        String body = mvc().perform(req).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    private String defaultStoreNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(0).get("storeNo").asString();
    }

    private String createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 5 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
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
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
