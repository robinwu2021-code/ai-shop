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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结算分店：三个维度各归各。
 *
 * <p>此前这三件事全挤在 {@code entity_no} 上：
 * <ul>
 *   <li>{@code store_no} —— 这笔钱是<b>哪家店</b>挣的（统计）</li>
 *   <li>{@code pay_merchant_no} —— 这笔钱打给<b>哪个账户</b>（结算）</li>
 *   <li>{@code entity_no} —— 谁开票、纳税、担责（合规）</li>
 * </ul>
 *
 * <p>拆开之后「分开结算 / 合并结算」是<b>配置的结果，不是开关</b>：
 * 两家店配同一个收款号就是合并，各配各的就是分开。
 *
 * <p>这个文件守四件事：
 * <ol>
 *   <li><b>单店行为逐字不变</b> —— 所有真实商家都是单店</li>
 *   <li>结算单落下两个快照，且二者互不决定</li>
 *   <li><b>改收款号不影响已生成的流水</b> —— 这是最贵的一条：
 *       实时解析会让改号把历史流水挪到新账户，退款时两个账户各错一笔且方向相反</li>
 *   <li>进件能按门店开 —— 不能的话「分开结算」在数据层就做不到</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreSettleFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 单店：解析出主体默认收款号，与改造前一致")
    void singleStoreResolvesEntityAccount() throws Exception {
        String biz = merchant("12600210001", "单店结算铺");
        String pm = activatePayment(biz, null);

        // 不传门店、传默认门店，解析结果必须一样 —— 单店时两个维度恒等
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), null)).contains(pm);
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), defaultStoreNo(biz))).contains(pm);
    }

    @Test
    @DisplayName("★ 两家店不配号 = 合并结算：解析出同一个收款号")
    void storesWithoutOwnAccountShareOne() throws Exception {
        String biz = merchant("12600210010", "合并结算·总店");
        String pm = activatePayment(biz, null);
        String storeA = defaultStoreNo(biz);
        // 多门店是 PRO 才有的能力，测试要说出「这家商家买了包」
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "合并结算·分店");

        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeA)).contains(pm);
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeB)).contains(pm);
    }

    @Test
    @DisplayName("★ 分店单独进件 + 挂号 = 分开结算：两家店解析出不同收款号")
    void storeWithOwnAccountSettlesSeparately() throws Exception {
        String biz = merchant("12600210020", "分开结算·总店");
        String entityPm = activatePayment(biz, null);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "分开结算·分店");

        // 为 B 店单独进件 —— 微信侧一个商户号只能绑一个结算账户，
        // 要两个账户就得进件两次
        String storePm = activatePayment(biz, storeB);
        assertThat(storePm).isNotEqualTo(entityPm);

        setStorePayment(biz, storeB, storePm);

        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeA)).contains(entityPm);
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeB)).contains(storePm);
    }

    @Test
    @DisplayName("★★ 改门店收款号，已生成的流水快照不跟着变 —— 否则退款会从新账户扣")
    void changingAccountDoesNotRewriteHistory() throws Exception {
        String biz = merchant("12600210030", "改号测试店");
        String entityPm = activatePayment(biz, null);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "改号测试·分店");
        String storePm = activatePayment(biz, storeB);

        // 先按「合并」下一单：此刻 B 店还没配自己的号
        String goodsNo = listedGoods(biz, 10);
        String skuNo = firstSku(goodsNo);
        assertThat(buy("13000210030", goodsNo, skuNo, 1, "sett-1")).isZero();

        var before = billsOf(biz);
        assertThat(before).isNotEmpty();
        String snapshot = before.get(0).get("payMerchantNo").asString();
        assertThat(snapshot).isEqualTo(entityPm);

        // 现在把 B 店改挂新号 —— 历史那条流水必须原样不动
        setStorePayment(biz, storeB, storePm);

        var after = billsOf(biz);
        assertThat(after.get(0).get("payMerchantNo").asString())
                .as("已生成的流水是快照，不随配置变 —— 钱已经进了旧账户")
                .isEqualTo(snapshot);
        // 而对**新的**订单，解析已经是新号了
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeB)).contains(storePm);
        assertThat(storeA).isNotEqualTo(storeB);
    }

    @Test
    @DisplayName("★ 结算流水落 store_no：门店报表按它聚合，与打给谁无关")
    void billCarriesStoreDimension() throws Exception {
        String biz = merchant("12600210040", "门店统计店");
        activatePayment(biz, null);
        String goodsNo = listedGoods(biz, 5);
        String skuNo = firstSku(goodsNo);
        assertThat(buy("13000210040", goodsNo, skuNo, 1, "sett-2")).isZero();

        var bills = billsOf(biz);
        assertThat(bills).isNotEmpty();
        assertThat(bills.get(0).get("storeNo").asString())
                .as("下单落在默认门店，结算要把它快照下来")
                .isEqualTo(defaultStoreNo(biz));
    }

    @Test
    @DisplayName("★★ 没有门店归属的存量流水，按门店筛时不能消失 —— 商家会以为钱没了")
    void legacyBillsWithoutStoreStaySkVisible() throws Exception {
        String biz = merchant("12600210060", "存量流水店");
        activatePayment(biz, null);
        String goodsNo = listedGoods(biz, 5);
        String skuNo = firstSku(goodsNo);
        assertThat(buy("13000210060", goodsNo, skuNo, 1, "sett-legacy")).isZero();

        // 模拟 V14 之前生成的行：把门店归属抹掉
        var bills = billsOf(biz);
        assertThat(bills).isNotEmpty();
        clearStoreOn(bills.get(0).get("settleNo").asString());

        // 开第二家店之后再看 —— 此时按门店筛是生效的
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        createStore(biz, "存量流水·分店");
        assertThat(billsOf(biz))
                .as("没有门店归属的行属于整个主体，任何一家店的视角都该看得到它")
                .isNotEmpty();
    }

    /**
     * <b>门店收窄真的收窄了。</b>
     *
     * <p>2026-08-31 补。此前这段逻辑（默认当前门店、{@code allStores=true} 才看全部）
     * 在 controller 里逐字写了两遍，而<b>一条测试都没有守着它</b> ——
     * 把它整段改成「永远返回全部门店」，56 条相关测试一条都不红。
     *
     * <p>它失效时不抛异常、不返 403：<b>店员打开收入页，看到的是别家店的钱，
     * 页面照常渲染。</b>越权在这里不是异常路径，是「多返回了几行」，
     * 所以必须由一条能证伪的断言盯着 —— 上面那次消融就是它的对照量。
     *
     * <p>判据取「两个方向都要成立」：不传参时看不见别家店的流水（收窄生效），
     * 传 {@code allStores=true} 时看得见（收窄不是把结果写死成空）。
     * 只断言前者的话，把实现改成「永远返回空」也能通过。
     */
    @Test
    @DisplayName("★★★ 门店收窄：站在 B 店只看得到 B 店的钱，allStores=true 才看得到 A 店的")
    void storeScopeActuallyNarrows() throws Exception {
        String biz = merchant("12600210070", "收窄判据店");
        activatePayment(biz, null);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "收窄判据·分店");

        // 在 A 店下一单 —— 商品挂在默认门店，流水的 store_no 就是 A
        String goodsNo = listedGoods(biz, 5);
        String skuNo = firstSku(goodsNo);
        assertThat(buy("13000210070", goodsNo, skuNo, 1, "sett-scope")).isZero();

        assertThat(billsOfStore(biz, storeA))
                .as("站在 A 店，A 店的流水当然看得到 —— 这条先立住，"
                        + "否则下面「B 店看不到」可能只是因为压根没生成流水")
                .isNotEmpty();

        assertThat(billsOfStore(biz, storeB))
                .as("站在 B 店却看到了 A 店的流水 —— 这就是越权，"
                        + "而页面上没有任何线索说明这一点")
                .isEmpty();

        assertThat(billsOfStore(biz, storeB, true))
                .as("allStores=true 要看得到全部授权门店的流水 —— "
                        + "少了这一条，把收窄实现成「永远返回空」也能过上一条")
                .isNotEmpty();
    }

    private java.util.List<tools.jackson.databind.JsonNode> billsOfStore(
            String token, String storeNo) throws Exception {
        return billsOfStore(token, storeNo, false);
    }

    /** 带 {@code X-Store-No} 走真实 HTTP —— 收窄发生在过滤器 + app service，测替身量不到 */
    private java.util.List<tools.jackson.databind.JsonNode> billsOfStore(
            String token, String storeNo, boolean allStores) throws Exception {
        var req = get("/biz/settle/bills" + (allStores ? "?allStores=true" : ""))
                .header("Authorization", "Bearer " + token)
                .header("X-Store-No", storeNo);
        String body = mvc().perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<tools.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
        for (var n : json.readTree(body).get("data")) {
            out.add(n);
        }
        return out;
    }

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.BillMapper billMapper;

    private void clearStoreOn(String settleNo) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var row = billMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.pay.entity.StlBill>lambdaQuery()
                    .eq(ai.neargo.shop.pay.entity.StlBill::getSettleNo, settleNo));
            row.setStoreNo(null);
            return billMapper.updateById(row);
        });
    }

    @Test
    @DisplayName("★ 别家主体的门店不能拿来开进件 —— 404 而不是 403")
    void cannotOpenPaymentForOthersStore() throws Exception {
        String mine = merchant("12600210050", "本主体");
        String other = merchant("12600210051", "别家主体");
        String othersStore = defaultStoreNo(other);

        mvc().perform(post("/biz/merchant/payment/store/" + othersStore)
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    // ---------------------------------------------------------------- 装配

    private java.util.List<tools.jackson.databind.JsonNode> billsOf(String token) throws Exception {
        String body = mvc().perform(get("/biz/settle/bills").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<tools.jackson.databind.JsonNode> out = new java.util.ArrayList<>();
        for (var n : json.readTree(body).get("data")) {
            out.add(n);
        }
        return out;
    }

    /** 进件到 ACTIVE，返回收款号。storeNo 为空 = 主体级 */
    private String activatePayment(String token, String storeNo) throws Exception {
        if (storeNo != null) {
            mvc().perform(post("/biz/merchant/payment/store/" + storeNo)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        String body = mvc().perform(post("/biz/merchant/payment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payChannel\":\"WECHAT\",\"settleAccount\":\"6222021234567890123\","
                                + "\"licenses\":[\"https://cdn/license.jpg\"],"
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\""
                                + (storeNo == null ? "" : ",\"storeNo\":\"" + storeNo + "\"")
                                + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.applyStatus").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payMerchantNo").asString();
    }

    private void setStorePayment(String token, String storeNo, String payMerchantNo) throws Exception {
        mvc().perform(post("/biz/store/" + storeNo + "/payment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMerchantNo\":\"" + payMerchantNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String merchantNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

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
        int code = json.readTree(body).get("code").asInt();
        if (code == 0) {
            // 结算单在**支付成功回调**之后才生成 —— 只调 /pay 拿到预支付单是不够的，
            // 那一步只是发起，钱还没到
            String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
            mvc().perform(post("/mp/order/" + payOrderNo + "/pay")
                    .header("Authorization", "Bearer " + buyer));
            mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idem
                            + "\",\"sign\":\"stub-secret\"}"));
        }
        return code;
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"结算测试品\",\"type\":\"NORMAL\","
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
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 3 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
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

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
