package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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
 * 门店定价（商品域-优化总方案 批 C）。
 *
 * <p>这组用例守的是取价链路上最容易裂的那一处：<b>展示的价与扣款的价必须是同一个</b>。
 * 「购物车显示门店价、下单按主体价扣钱」与「预览显示特价、支付按原价」是同一个形状 ——
 * 而它不报错，只在对账时表现成钱对不上。
 */
@SpringBootTest
@ActiveProfiles("test")
class StorePriceFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 定了本店价，下单就按本店价扣 —— 展示与扣款是同一个数")
    void storePriceReachesCheckout() throws Exception {
        String biz = merchant("12600151001", "门店定价·总店");
        String goodsNo = saveGoods(biz, 1000);
        String skuNo = firstSku(goodsNo);
        approveGoods(goodsNo);
        publish(biz, goodsNo);

        // 本店价 ¥7.00（主体价 ¥10.00）
        setStorePrice(biz, goodsNo, skuNo, "700");

        long amount = buyOnce("12600151002", goodsNo, skuNo, "sp-1");
        assertThat(amount).as("按本店价扣，不是主体价").isEqualTo(700);
    }

    @Test
    @DisplayName("★ 没定过本店价的店按主体价卖 —— 回退方向与库存相反，视为 0 就是白送")
    void withoutStorePriceFallsBackToEntityPrice() throws Exception {
        String biz = merchant("12600151003", "门店定价·未定价");
        String goodsNo = saveGoods(biz, 1000);
        String skuNo = firstSku(goodsNo);
        approveGoods(goodsNo);
        publish(biz, goodsNo);

        long amount = buyOnce("12600151004", goodsNo, skuNo, "sp-2");
        assertThat(amount).as("回退主体价，而不是 0").isEqualTo(1000);
    }

    @Test
    @DisplayName("★ 传空 = 取消本店单独定价 —— 没有这条，定过价就再也回不到主体价")
    void clearingStorePriceGoesBack() throws Exception {
        String biz = merchant("12600151005", "门店定价·撤回");
        String goodsNo = saveGoods(biz, 1000);
        String skuNo = firstSku(goodsNo);
        approveGoods(goodsNo);
        publish(biz, goodsNo);

        setStorePrice(biz, goodsNo, skuNo, "700");
        // 商家侧读得回自己定的价：只回主体价的话，他不知道这家店现在卖多少
        assertThat(storePriceOf(biz, goodsNo, skuNo)).isEqualTo(700);

        setStorePrice(biz, goodsNo, skuNo, "null");
        assertThat(storePriceOf(biz, goodsNo, skuNo)).as("空 = 同主体价，不是 0").isNull();

        long amount = buyOnce("12600151006", goodsNo, skuNo, "sp-3");
        assertThat(amount).isEqualTo(1000);
    }

    // ---------------------------------------------------------------- helpers

    /** @return 这笔订单的商品金额 */
    private long buyOnce(String buyerPhone, String goodsNo, String skuNo, String idem) throws Exception {
        String buyer = TestLogin.consumer(mvc(), json, otpStore, buyerPhone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        // 下单返回的是主单；商品金额在 amount.goodsMinor 上
        return data.get("amount").get("goodsMinor").asLong();
    }

    private void setStorePrice(String token, String goodsNo, String skuNo, String price) throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/store-price")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"" + skuNo + "\",\"price\":" + price + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Long storePriceOf(String token, String goodsNo, String skuNo) throws Exception {
        String body = mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(s.get("skuNo").asString())) {
                JsonNode sp = s.get("storePrice");
                return sp == null || sp.isNull() ? null : sp.asLong();
            }
        }
        return null;
    }

    private String saveGoods(String token, long price) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"门店定价测试品\",\"subtitle\":\"测试\","
                                + "\"cover\":\"c.jpg\",\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":" + price + ",\"stock\":50}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void publish(String token, String goodsNo) throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
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
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        return opsLogin("goods", "goods123");
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
