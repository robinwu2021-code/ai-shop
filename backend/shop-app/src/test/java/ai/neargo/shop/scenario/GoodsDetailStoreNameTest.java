package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 商品详情店铺卡显示**门店名**而不是主体名（TDD-商品详情店铺卡门店名）。
 *
 * <p>根因：`detailForBuyer` 收到 storeNo 只换库存、从不填 `GoodsVO.store`，于是店铺卡恒显主体名。
 * 用户 2026-10-04：「每个商品都关联门店，根据商品查门店，不论从哪进来」。
 *
 * <p>门店名取成**可控的、明确不等于主体名**的值，才验得出「显示的是门店名」——
 * 快速开店的默认店名可能恰好等于商家名，那样断言会假绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsDetailStoreNameTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ AC1 带门店号进详情：店铺卡是那家门店名，不是主体名")
    void detailWithStoreNoShowsStoreName() throws Exception {
        String biz = merchant("12600190001", "门店名科技有限公司");
        // 用默认营业门店（上架类目与它一致，不触发跨店冲突）；改名成明确不等于主体名的值，
        // 否则默认店名若恰好等于商家名，「显示的是门店名」这条就验不出来（假绿）
        String storeA = defaultStoreNo(biz);
        renameStore(storeA, "门店名测试·甲店");
        String goodsNo = onSaleGoodsAt(biz, storeA, "只在甲店卖的抽纸 A");

        JsonNode data = data(get("/mp/goods/" + goodsNo).param("storeNo", storeA));
        assertThat(data.get("store").isNull()).as("store 被填上了").isFalse();
        assertThat(data.get("store").get("storeNo").asString()).isEqualTo(storeA);
        assertThat(data.get("store").get("storeName").asString()).isEqualTo("门店名测试·甲店");
        // 主体名仍在 merchant 里（资质入口用），但它不该是买家看到的店名
        assertThat(data.get("merchant").get("name").asString()).isEqualTo("门店名科技有限公司");
        assertThat(data.get("store").get("storeName").asString())
                .isNotEqualTo(data.get("merchant").get("name").asString());
    }

    @Test
    @DisplayName("★★★ AC2 不带门店号：按商品唯一在售店解析，仍显门店名（首页推荐/搜索/购物车进来）")
    void detailWithoutStoreNoResolvesByGoods() throws Exception {
        String biz = merchant("12600190002", "无号科技有限公司");
        String storeA = defaultStoreNo(biz);
        renameStore(storeA, "无号测试·甲店");
        String goodsNo = onSaleGoodsAt(biz, storeA, "只在甲店卖的抽纸 B");

        // 不带 storeNo —— 模拟从首页推荐 / 搜索进来
        JsonNode data = data(get("/mp/goods/" + goodsNo));
        assertThat(data.get("store").isNull()).as("不带门店号也要按商品归属门店填上").isFalse();
        assertThat(data.get("store").get("storeNo").asString())
                .as("商品只在甲店在售 → 唯一在售店").isEqualTo(storeA);
        assertThat(data.get("store").get("storeName").asString()).isEqualTo("无号测试·甲店");
    }

    // ---- helpers（照 StoreScopedVisibilityFlowTest 的模式，独立一份，不动那个共享文件） ----

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String user, String pwd) throws Exception {
        return json.readTree(mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("token").asString();
    }

    private String defaultStoreNo(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("currentStoreNo").asString();
    }

    /** 直接改门店名（门店名改名没有 /biz 接口；测试里改库最可控，且这就是要验的「门店表的 name」） */
    private void renameStore(String storeNo, String name) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            ai.neargo.shop.merchant.entity.MchStore st = storeMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                            .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo).last("limit 1"));
            st.setName(name);
            storeMapper.updateById(st);
            return null;
        });
    }

    private String saveGoods(String token, String storeNo, String title) throws Exception {
        // 类目**只对要上架的那家店**开 —— 一个类目开在另一家店，上架时会 70075（跨店类目）
        TestStoreCategory.open(mvc(), json, token, storeNo, "CAT210");
        return json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\","
                                + "\"subtitle\":\"\",\"cover\":\"🧻\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("goods", "goods123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String onSaleGoodsAt(String token, String storeNo, String title) throws Exception {
        String goodsNo = saveGoods(token, storeNo, title);
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }
}
