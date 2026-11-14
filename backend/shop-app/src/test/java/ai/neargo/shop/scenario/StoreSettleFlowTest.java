package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestStoreCategory;
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
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), null, "WECHAT")).contains(pm);
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), defaultStoreNo(biz), "WECHAT")).contains(pm);
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

        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeA, "WECHAT")).contains(pm);
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeB, "WECHAT")).contains(pm);
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

        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeA, "WECHAT")).contains(entityPm);
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeB, "WECHAT")).contains(storePm);
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
        assertThat(merchantQueryPort.payMerchantNoOf(merchantNo(biz), storeB, "WECHAT")).contains(storePm);
        assertThat(storeA).isNotEqualTo(storeB);
    }

    // ---------------------------------------------------------------- F01 · 按渠道解析收款号（Review R01）

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper paymentMapper;

    /** 直插一行 ACTIVE 收款号：渠道与门店维度由测试点名，不走进件接口 */
    private String activeAccount(String merchantNo, String storeNo, String channel, String payMerchantNo) {
        var pm = new ai.neargo.shop.merchant.entity.MchPaymentMerchant();
        pm.setEntityNo(merchantNo);
        pm.setStoreNo(storeNo == null ? ai.neargo.shop.merchant.entity.MchPaymentMerchant.ENTITY_LEVEL : storeNo);
        pm.setPayChannel(channel);
        pm.setApplyStatus(ai.neargo.shop.merchant.entity.MchPaymentMerchant.ACTIVE);
        pm.setPayMerchantNo(payMerchantNo);
        pm.setSubMchid("SUB-" + payMerchantNo);
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(pm));
        return payMerchantNo;
    }

    @Test
    @DisplayName("★★★ F01 支付宝的单只取支付宝号 —— 不按渠道过滤时会取到先建的微信号，打款打错账户")
    void resolvesByChannel() throws Exception {
        String biz = merchant("12600210080", "双渠道店");
        String m = merchantNo(biz);
        String store = defaultStoreNo(biz);
        String wechat = activatePayment(biz, null);
        String alipay = activeAccount(m, null, "ALIPAY", "PM-F01-ALI-" + m);

        assertThat(merchantQueryPort.payMerchantNoOf(m, store, "ALIPAY")).contains(alipay);
        assertThat(merchantQueryPort.payMerchantNoOf(m, store, "WECHAT")).contains(wechat);
        assertThat(merchantQueryPort.payMerchantNoOf(m, store, "UNIONPAY"))
                .as("这个渠道一个号都没有 —— 宁可空着（账单照出、打不出去），也不能借别的渠道的号")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ F01 兜底不取别家门店的专属号 —— A 店没配号时，B 店单独进件的号不是 A 的")
    void fallbackNeverBorrowsAnotherStoresAccount() throws Exception {
        String biz = merchant("12600210081", "专属号店");
        String m = merchantNo(biz);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "专属号·分店");
        activeAccount(m, storeB, "ALIPAY", "PM-F01-B-" + m);

        assertThat(merchantQueryPort.payMerchantNoOf(m, storeA, "ALIPAY"))
                .as("支付宝没有主体级号，只有 B 店的专属号 —— A 店的钱不能打进 B 店的账户")
                .isEmpty();
        assertThat(merchantQueryPort.payMerchantNoOf(m, storeB, "ALIPAY"))
                .as("对照：B 店自己的单当然取它").contains("PM-F01-B-" + m);
    }

    @Test
    @DisplayName("★★ F01 线下 / 免单不经通道：不按渠道过滤，但取号顺序确定（最早建的），且同样不借别店专属号")
    void offlineKeepsEntityDefaultDeterministically() throws Exception {
        String biz = merchant("12600210082", "线下店");
        String m = merchantNo(biz);
        String store = defaultStoreNo(biz);
        String wechat = activatePayment(biz, null);
        activeAccount(m, null, "ALIPAY", "PM-F01-ALI2-" + m);

        for (String channel : new String[] {"OFFLINE", "FREE", null}) {
            assertThat(merchantQueryPort.payMerchantNoOf(m, store, channel)).as(String.valueOf(channel)).contains(wechat);
        }
    }

    // ---------------------------------------------------------------- F02 · 一处解析（Review R02 / R26）

    @Autowired
    private ai.neargo.shop.spi.user.MerchantAdminPort merchantAdminPort;

    @Autowired
    private ai.neargo.shop.merchant.service.MerchantGovernService governService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ F02 能力 / 额度与结算解析到同一个收款号 —— 门店绑了微信号，能力却从支付宝那行读，支付方式与额度就判错")
    void capabilityFollowsTheSameAccountAsSettlement() throws Exception {
        String biz = merchant("12600210084", "一处解析店");
        String m = merchantNo(biz);
        String store = defaultStoreNo(biz);
        String wechat = activatePayment(biz, null);
        String alipay = activeAccount(m, null, "ALIPAY", "PM-F02-ALI-" + m);
        jdbc.update("UPDATE mch_payment_merchant SET pay_methods='[\"APP\"]' WHERE pay_merchant_no=?", alipay);
        jdbc.update("UPDATE mch_payment_merchant SET pay_methods='[\"JSAPI\"]' WHERE pay_merchant_no=?", wechat);
        // 门店显式绑微信号。旧的能力解析不看门店绑定，主体级 LIMIT 1 在 H2 上按唯一索引取到 ALIPAY
        setStorePayment(biz, store, wechat);

        assertThat(merchantQueryPort.payMerchantNoOf(m, store, "OFFLINE")).contains(wechat);
        assertThat(merchantQueryPort.payCapabilityOf(m, store).payMethods())
                .as("门店绑的是微信号，能力就该读微信那一行")
                .containsExactly("JSAPI");

        merchantAdminPort.accruePayQuota(m, store, "ALIPAY", 1_234L);
        assertThat(jdbc.queryForObject("SELECT quota_used_minor FROM mch_payment_merchant WHERE pay_merchant_no=?",
                Long.class, alipay)).as("支付宝的单记到支付宝号上，不记到门店绑的微信号").isEqualTo(1_234L);
        merchantAdminPort.accruePayQuota(m, store, "WECHAT", 100L);
        assertThat(jdbc.queryForObject("SELECT quota_used_minor FROM mch_payment_merchant WHERE pay_merchant_no=?",
                Long.class, wechat)).isEqualTo(100L);
    }

    @Test
    @DisplayName("★★★ F02 · R26 门店换了主体，不再认旧主体为它进件的号 —— 只按 store_no 查会把新主体的钱打进旧主体")
    void storeMovedToAnotherEntityDropsOldEntitysAccount() throws Exception {
        String oldBiz = merchant("12600210085", "旧主体");
        TestPlan.grantPro(mvc(), json, planMapper, oldBiz);
        String movingStore = createStore(oldBiz, "要搬走的店");
        activatePayment(oldBiz, movingStore);
        String newBiz = merchant("12600210086", "新主体");
        String newM = merchantNo(newBiz);
        jdbc.update("UPDATE mch_store SET entity_no=? WHERE store_no=?", newM, movingStore);

        var mode = governService.storeModes(newM).stream()
                .filter(v -> movingStore.equals(v.storeNo())).findFirst().orElseThrow();
        assertThat(mode.payMerchantNo())
                .as("新主体一个收款号都没有 —— 读到的号是旧主体为这家店开的，钱会进旧主体的账户")
                .isNull();
    }

    @Test
    @DisplayName("★★★ F03 B 店专属号不能绑给 A 店 —— 绑上之后 A 店的钱进了 B 店的结算账户")
    void cannotBindAnotherStoresAccount() throws Exception {
        String biz = merchant("12600210083", "绑号校验店");
        String storeA = defaultStoreNo(biz);
        String entityPm = activatePayment(biz, null);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "绑号校验·分店");
        String storeBPm = activatePayment(biz, storeB);

        mvc().perform(post("/biz/store/" + storeA + "/payment")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMerchantNo\":\"" + storeBPm + "\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        // 对照：主体级号给哪家店都行，B 店的号绑回 B 店也行
        setStorePayment(biz, storeA, entityPm);
        setStorePayment(biz, storeB, storeBPm);
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
            mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idem
                            + "\",\"sign\":\"stub-secret\"}"));
        }
        return code;
    }

    private String listedGoods(String token, int stock) throws Exception {
        TestStoreCategory.open(mvc(), json, token, "CAT210");
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
