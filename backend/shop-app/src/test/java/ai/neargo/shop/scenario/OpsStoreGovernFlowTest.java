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
 * 运营端门店与商品治理（TDD-运营端门店与商品治理，P-11.2.1 / P-3.2.3）。
 *
 * <p>这个文件守三条真链路，每条都是「只改状态位不动真实闸门」最容易假装完成的地方：
 * <ol>
 *   <li><b>强制下架必须落到买家看得见的地方</b> —— REJECTED 只是标；
 *       撤池之后买不到才是处置真的发生了
 *   <li><b>门店强制下线商家不能自救</b> —— 自救能绕开的处置等于没有处置
 *   <li><b>解除只恢复平台压下去的</b> —— 商家在处置期间自己下架的东西，
 *       解除时不能被平台顺手重新上架
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsStoreGovernFlowTest {

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

    // ---------------------------------------------------------------- 门店档案（P-11.2.1）

    @Test
    @DisplayName("★ 门店档案：按主体列门店（含停用的）· 详情 · 经营数据")
    void storeDirectory() throws Exception {
        String biz = merchant("12600270001", "门店档案·总店");
        String merchantNo = merchantNoOf(biz);
        // 多门店是 PRO 才有的能力，测试要说出「这家商家买了包」
        TestPlan.grantPro(planMapper, merchantNo);
        String storeB = createStore(biz, "门店档案·分店");
        String ops = opsLogin();

        // 分店先停掉 —— 治理视角必须看得见停用的店
        mvc().perform(post("/biz/store/" + storeB + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        String body = mvc().perform(get("/ops/stores").param("merchantNo", merchantNo)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data").get("records");
        assertThat(rows).hasSize(2);
        /*
         * 每一行都必须是这个主体的。
         *
         * 这条断言是被一次真实的假绿逼出来的：这个类原先用的手机号段与
         * StoreSettleFlowTest 逐个重合，全量跑时复用了同一个账号，
         * 于是这里拿到的是**别人商家的门店**。单独跑时库里只有自己的数据，
         * 「筛选失效」与「筛选正确」看起来一模一样 —— 只数行数是数不出来的。
         */
        for (JsonNode r : rows) {
            assertThat(r.get("merchantNo").asString()).isEqualTo(merchantNo);
        }

        JsonNode readonly = findByStatus(rows, "READONLY");
        assertThat(readonly.get("storeNo").asString()).isEqualTo(storeB);
        assertThat(readonly.get("merchantName").asString()).contains("门店档案");

        // 详情带门面与配送规则字段
        String storeA = findByStatus(rows, "ACTIVE").get("storeNo").asString();
        mvc().perform(get("/ops/stores/" + storeA).header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.merchantNo").value(merchantNo));

        // 经营数据：复用 B 端那套 stats（不另存计数器），刚开的店全是 0
        mvc().perform(get("/ops/stores/" + storeA + "/stats").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.data.todayOrders").value(0))
                .andExpect(jsonPath("$.data.merchantNo").value(merchantNo));

        // 不存在的门店 404 语义（业务码非 0），不是 500
        String miss = mvc().perform(get("/ops/stores/ST_NOPE/stats").header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(miss).get("code").asInt()).isNotEqualTo(0);
    }

    // ---------------------------------------------------------------- 强制下架（P-3.2.3）

    @Test
    @DisplayName("★ 强制下架 = 撤销过审：C 端买不到、B 端看到原因、改后重新提审")
    void forceOffGoods() throws Exception {
        String biz = merchant("12600270010", "强制下架店");
        String goodsNo = listedGoods(biz, 10);
        String skuNo = firstSku(goodsNo);
        String ops = opsLogin();

        // 处置前买得到 —— 这一步不是废话：它证明后面的失败是处置造成的
        assertThat(buy("13000270011", goodsNo, skuNo, 1, "fo-1")).isEqualTo(0);

        // 无原因 → 拒绝
        String noReason = mvc().perform(post("/ops/goods/" + goodsNo + "/force-off")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(noReason).get("code").asInt()).isNotEqualTo(0);

        mvc().perform(post("/ops/goods/" + goodsNo + "/force-off")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"主图与实物不符\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // B 端看到状态与原因 —— 原因是商家能看到的那半边，只写审计日志他只能猜
        mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.auditReason")
                        .value("平台强制下架：主图与实物不符"));

        // C 端买不到了 —— 撤池 + 下架双保险落到了买家侧
        assertThat(buy("13000270012", goodsNo, skuNo, 1, "fo-2")).isNotEqualTo(0);

        // 商家改后重新提审：走的是既有链路，回到 PENDING
        mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"换了主图的测试品\","
                                // 类目必填（P1-1 收尾）：CAT210 无 required_code，不卡资质
                                + "\"categoryNo\":\"CAT210\","
                                + "\"skus\":[{\"skuNo\":\"" + skuNo + "\",\"optionValues\":[],"
                                + "\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // ---------------------------------------------------------------- 门店强制下线（P-11.2.1 / V96）

    @Test
    @DisplayName("★ 门店强制下线：C 端已停业、商家不能自救、解除后原样恢复")
    void storeOfflineAndRestore() throws Exception {
        String biz = merchant("12600270020", "强制下线店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 10);
        String skuNo = firstSku(goodsNo);
        String storeNo = defaultStoreNo(biz);
        String ops = opsLogin();

        assertThat(buy("13000270021", goodsNo, skuNo, 1, "so-1")).isEqualTo(0);

        // 门店级处置必须带门店号；主体级处置不能带 —— 两个方向都拦
        String badReq = mvc().perform(post("/ops/merchants/" + merchantNo + "/violations")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SERVICE\",\"action\":\"STORE_OFFLINE\","
                                + "\"detail\":\"少了门店号\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(badReq).get("code").asInt()).isNotEqualTo(0);

        mvc().perform(post("/ops/merchants/" + merchantNo + "/violations")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SERVICE\",\"action\":\"STORE_OFFLINE\","
                                + "\"storeNo\":\"" + storeNo + "\",\"detail\":\"卫生投诉核实\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storeNo").value(storeNo));

        // C 端门店主页盖「已停业」—— 是标志不是 404：扫码进来的老客要知道是店关了
        mvc().perform(get("/mp/store/" + merchantNo))
                .andExpect(jsonPath("$.data.closed").value(true));

        // 货架真的撤了 —— 只改 status 的话这一步会成功，处置就是假的
        assertThat(buy("13000270022", goodsNo, skuNo, 1, "so-2")).isNotEqualTo(0);

        // 商家自救被拒（70021 专用码 —— 他该联系平台，不是反复点启用）
        String rescue = mvc().perform(post("/biz/store/" + storeNo + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(rescue).get("code").asInt()).isEqualTo(70021);

        // 留痕可按门店检索
        String violations = mvc().perform(get("/ops/merchants/violations")
                        .param("merchantNo", merchantNo)
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(violations).get("data").get("records").get(0);
        assertThat(row.get("storeNo").asString()).isEqualTo(storeNo);
        assertThat(row.get("action").asString()).isEqualTo("STORE_OFFLINE");

        // 解除 → 已停业消失、货架原样回来
        mvc().perform(post("/ops/stores/" + storeNo + "/restore")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc().perform(get("/mp/store/" + merchantNo))
                .andExpect(jsonPath("$.data.closed").value(false));
        assertThat(buy("13000270023", goodsNo, skuNo, 1, "so-3")).isEqualTo(0);
    }

    @Test
    @DisplayName("★ 解除只恢复平台压下去的 —— 处置期间商家自己下架的不跟着回架")
    void restoreDoesNotResurrectMerchantOwnChoices() throws Exception {
        String biz = merchant("12600270030", "自主下架不被复活");
        String merchantNo = merchantNoOf(biz);
        String storeNo = defaultStoreNo(biz);
        String keepOff = listedGoods(biz, 5);
        String forced = listedGoods(biz, 5);
        String ops = opsLogin();

        // 商家先自己下架 keepOff —— 这是他的经营决定，与处置无关
        mvc().perform(post("/biz/goods/" + keepOff + "/toggle")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/ops/merchants/" + merchantNo + "/violations")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SERVICE\",\"action\":\"STORE_OFFLINE\","
                                + "\"storeNo\":\"" + storeNo + "\",\"detail\":\"处置理由\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/stores/" + storeNo + "/restore")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));

        // forced 回架、keepOff 仍是商家自己选的下架 —— 平台不替商家上架
        mvc().perform(get("/biz/goods/" + forced).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));
        mvc().perform(get("/biz/goods/" + keepOff).header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"));
    }

    @Test
    @DisplayName("解除只对 SUSPENDED 有意义 —— 对正常门店 restore 被拒")
    void restoreRequiresSuspended() throws Exception {
        String biz = merchant("12600270040", "无处置可解除");
        String storeNo = defaultStoreNo(biz);
        String body = mvc().perform(post("/ops/stores/" + storeNo + "/restore")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isNotEqualTo(0);
    }

    // ---------------------------------------------------------------- 装配（照 StoreStockFlowTest 的形状）

    /** @return 下单响应的 code，0 = 成功 */
    private int buy(String phone, String goodsNo, String skuNo, int qty, String idem) throws Exception {
        String buyer = login(phone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"门店治理测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":" + stock + "}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        return goodsNo;
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
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
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 9 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
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
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private JsonNode findByStatus(JsonNode rows, String status) {
        for (JsonNode r : rows) {
            if (status.equals(r.get("status").asString())) {
                return r;
            }
        }
        throw new AssertionError("没有 status=" + status + " 的门店行：" + rows);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
