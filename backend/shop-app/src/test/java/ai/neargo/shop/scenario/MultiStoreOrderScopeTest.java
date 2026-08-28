package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多门店的订单作用域 —— **先确认地基是实的，再动库存那种高风险改造**。
 *
 * <p>背景：`ord_sub_order.store_no` 一直在双写（实测填充率 100%），
 * 后端 `/biz/order` 也一直支持 `allStores` 参数并区分了「老板的全部」与
 * 「店员被授权的那几家」—— 但**端上从没传过这个参数**，
 * 于是订单页恒等于「当前门店」，界面上既不显示是哪家店也没有切换入口。
 * 单店时看不出区别，多店老板会以为自己看到的是全部流水。
 *
 * <p>这个文件测的是：双写有没有真写对、按门店过滤是不是真的分得开。
 * 这两条成立，后面的库存分店才有地基。
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiStoreOrderScopeTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 开第二家店后，默认只看到当前门店的单；allStores=true 才看到全部")
    void ordersAreScopedToCurrentStoreByDefault() throws Exception {
        String token = merchant("12600180001", "双店测试·总店");
        String defaultStore = defaultStoreNo(token);
        // 多门店是 PRO 才有的能力，测试要说出「这家商家买了包」
        TestPlan.grantPro(planMapper, merchantNoOf(token));
        String second = createStore(token, "双店测试·分店");
        assertThat(second).isNotEqualTo(defaultStore);

        // 在总店下一单（不传 X-Store-No 即默认店）
        buyFrom(token, "13000180001", "scope-1");

        // 切到分店：一单都看不到 —— 这单是在总店履约的
        assertThat(orderCount(token, second, false)).isZero();
        // 回到总店：看得到
        assertThat(orderCount(token, defaultStore, false)).isEqualTo(1);
        // 老板看全部：也看得到
        assertThat(orderCount(token, second, true)).isEqualTo(1);
    }

    @Test
    @DisplayName("订单上的 store_no 真的被写了 —— 双写没写对的话上面那条会假绿")
    void subOrderCarriesStoreNo() throws Exception {
        String token = merchant("12600180002", "双写校验店");
        buyFrom(token, "13000180002", "scope-2");

        JsonNode rows = orders(token, null, true);
        assertThat(rows).isNotEmpty();
        /*
         * 直接查库：VO 不下发 storeNo，只测接口的话「过滤生效」与
         * 「一条都没写但恰好都被过滤掉了」分不开。
         */
        String merchantNo = merchantNoOf(token);
        var subs = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.trade.entity.OrdSubOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdSubOrder::getEntityNo, merchantNo)));
        assertThat(subs).isNotEmpty();
        assertThat(subs).allSatisfy(s -> assertThat(s.getStoreNo()).isNotBlank());
    }

    // ---------------------------------------------------------------- 装配

    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;

    private String merchantNoOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
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
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 2 号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    private JsonNode orders(String token, String storeNo, boolean all) throws Exception {
        var req = get("/biz/order?size=50" + (all ? "&allStores=true" : ""))
                .header("Authorization", "Bearer " + token);
        if (storeNo != null) {
            req = req.header("X-Store-No", storeNo);
        }
        String body = mvc().perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    private int orderCount(String token, String storeNo, boolean all) throws Exception {
        return orders(token, storeNo, all).size();
    }

    /** 用另一个买家在这家店买一次，产出一笔子订单 */
    private void buyFrom(String bizToken, String buyerPhone, String idem) throws Exception {
        String goodsNo = saveGoods(bizToken);
        approveGoods(goodsNo);
        publish(bizToken, goodsNo);

        String buyer = login(buyerPhone);
        String skuNo = firstSku(goodsNo);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String saveGoods(String token) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"门店作用域测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":50}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("goodsNo").asString();
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

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
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
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
