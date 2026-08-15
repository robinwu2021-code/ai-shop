package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
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
 * 运营端增长与归因（TDD-运营端增长与归因，P-9.1 / P-9.2）。
 *
 * <p><b>归因规则不是一张配置表，它直接决定商家付多少佣金</b>（ADR-004 §6：
 * {@code STORE_CODE → MERCHANT_OWNED} 低费率，其余 → {@code PLATFORM} 正常费率）。
 * 所以这个类里没有一条「存得下就算过」的断言 —— 每条规则都要证明它**改变了判定结果**：
 * <ol>
 *   <li><b>优先级</b>：同一份线索（既有店铺码又有邀请人），把 INVITER 提到最前，
 *       同一单的 {@code trafficSource} 就从 MERCHANT_OWNED 变成 PLATFORM。
 *       <b>这一条变红等于商家的账单算错了</b></li>
 *   <li><b>冲突策略</b>：{@code KEEP_FIRST} 下扫第二家店不改归属，
 *       {@code OVERWRITE} 下改 —— 而留痕两种都要写得下</li>
 *   <li><b>窗口期</b>：改成 1 天，新归属的到期时刻跟着变</li>
 *   <li><b>审计走真链路</b>：扫码进店 → 下单付款 → 运营端在归因链路里查得到这个人这一条</li>
 * </ol>
 *
 * <p>手机号段 {@code 126005xxxxx}（商户）/ {@code 130005xxxxx}（买家），尾号 3xxxx 段归本类。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsGrowthFlowTest {

    private static final String STUB_SECRET = "stub-secret";
    private static final String DEFAULT_PRIORITY = "\"STORE_CODE\",\"INVITER\",\"CHANNEL\"";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MchEntityMapper merchantMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /**
     * 规则是**全平台单行**，且被归因引擎每次判定时读取。
     * 本类改它，跑完必须还原成出厂值 —— 留一个被改过的优先级给后面的用例，
     * 表现会是「某个完全无关的测试里商家客流判成了平台客流」。
     */
    @AfterEach
    void restoreFactoryRule() throws Exception {
        saveRule(opsLogin(), DEFAULT_PRIORITY, 30, "OVERWRITE", "\"DEVICE\",\"PHONE\"");
    }

    // ---------------------------------------------------------------- 真链路审计（9.1.3）

    @Test
    @DisplayName("★ 扫码进店 → 下单付款 → 运营端归因链路里查得到这一单，且判定与规则一致")
    void storeCodeJourneyIsAuditable() throws Exception {
        String ops = opsLogin();
        saveRule(ops, DEFAULT_PRIORITY, 30, "OVERWRITE", "\"DEVICE\",\"PHONE\"");

        String biz = merchant("12600530001", "增长·扫码进店测试店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 20);
        String skuNo = firstSku(goodsNo);

        String buyer = login("13000530011");
        String userNo = userNoOf(buyer);

        /*
         * C 端扫码进店。
         *
         * ⚠️ TDD §4.2 要求这条链路顺带采 deviceId / IP（P-16.2.2 异常裂变靠它），
         * 但 {@code AttributionService.Clue} 今天只有三个分量、{@code EnterReq} 也没有那两个字段 ——
         * 所以这里不发它们：发一个后端根本不读的字段，等于给这条断言镀一层假的完成度。
         * 这一块的缺口写在验收报告里。
         */
        mvc().perform(post("/mp/store/" + merchantNo + "/enter")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"" + storeCodeOf(merchantNo) + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.source").value("STORE_CODE"))
                .andExpect(jsonPath("$.data.merchantNo").value(merchantNo))
                // 自带客流 = 低费率档。这个字段就是佣金档本身
                .andExpect(jsonPath("$.data.trafficSource").value("MERCHANT_OWNED"));

        buyAndPay(buyer, goodsNo, skuNo, "growth-1");
        assertThat(latestOrder(buyer).get("trafficSource").asString())
                .as("扫码进店后的这一单必须固化成商家自带客流").isEqualTo("MERCHANT_OWNED");

        /*
         * 运营端审计读的是**真实的归因决策日志**，不是另造的一份平行数据。
         * 另造那份迟早与实际判定分岔，而分岔时商家看到的是
         * 「审计说算我的，账单说不算」。
         */
        JsonNode trace = traceOf(ops, userNo);
        assertThat(trace).as("归因链路里必须查得到刚才那次判定").isNotNull();
        assertThat(trace.get("source").asString()).isEqualTo("STORE_CODE");
        assertThat(trace.get("sourceRef").asString())
                .as("sourceRef 要是能查的编号，不是给人读的店名").isEqualTo(merchantNo);
        assertThat(trace.get("decision").asString()).isEqualTo("CREATED");
        assertThat(trace.get("attributedAt").asString()).isNotBlank();
    }

    // ---------------------------------------------------------------- 优先级驱动佣金档（9.1.1）

    @Test
    @DisplayName("★ 改优先级 → 同一份线索判定跟着变 → 商家佣金档跟着变")
    void priorityDrivesTrafficSourceAndFeeTier() throws Exception {
        String ops = opsLogin();
        String biz = merchant("12600530010", "增长·优先级测试店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 20);
        String skuNo = firstSku(goodsNo);

        // ---- 出厂顺序：店铺码最强
        saveRule(ops, DEFAULT_PRIORITY, 30, "OVERWRITE", "\"DEVICE\",\"PHONE\"");
        String buyerA = login("13000530021");
        report(buyerA, merchantNo, "U-GROWTH-INVITER", null);
        assertThat(reportSource(buyerA, merchantNo, "U-GROWTH-INVITER")).isEqualTo("STORE_CODE");
        buyAndPay(buyerA, goodsNo, skuNo, "growth-prio-a");
        assertThat(latestOrder(buyerA).get("trafficSource").asString())
                .isEqualTo("MERCHANT_OWNED");

        // ---- 把邀请人提到最前：**同一份线索**，判定必须变
        saveRule(ops, "\"INVITER\",\"STORE_CODE\",\"CHANNEL\"", 30, "OVERWRITE",
                "\"DEVICE\",\"PHONE\"");
        assertThat(rule(ops).get("priority").get(0).asString()).isEqualTo("INVITER");

        String buyerB = login("13000530022");
        assertThat(reportSource(buyerB, merchantNo, "U-GROWTH-INVITER"))
                .as("优先级改了，同样的线索必须判成 INVITER —— 否则那一页是个摆设")
                .isEqualTo("INVITER");
        buyAndPay(buyerB, goodsNo, skuNo, "growth-prio-b");
        assertThat(latestOrder(buyerB).get("trafficSource").asString())
                .as("邀请人带来的客户不算商家自带客流，费率档不同（ADR-004 §6）")
                .isEqualTo("PLATFORM");
    }

    // ---------------------------------------------------------------- 冲突策略与窗口期（9.1.2 / 9.1.4）

    @Test
    @DisplayName("★ 冲突策略真的在裁决：KEEP_FIRST 不覆盖并留 KEPT 痕，OVERWRITE 覆盖")
    void conflictPolicyDecidesAndLeavesTrace() throws Exception {
        String ops = opsLogin();
        String storeA = merchantNoOf(merchant("12600530020", "增长·冲突 A 店"));
        String storeB = merchantNoOf(merchant("12600530021", "增长·冲突 B 店"));

        // ---- KEEP_FIRST：先到先得
        saveRule(ops, DEFAULT_PRIORITY, 30, "KEEP_FIRST", "\"DEVICE\",\"PHONE\"");
        String keeper = login("13000530031");
        String keeperNo = userNoOf(keeper);
        report(keeper, storeA, null, null);
        JsonNode kept = report(keeper, storeB, null, null);
        assertThat(kept.get("merchantNo").asString())
                .as("KEEP_FIRST 下扫第二家店不该改归属").isEqualTo(storeA);
        /*
         * 「为什么没算我的」与「为什么算了我的」是同样多的提问。
         * KEPT 这条痕不写的话，只能回答一半。
         */
        assertThat(traceOf(ops, keeperNo).get("decision").asString()).isEqualTo("KEPT");

        // ---- OVERWRITE：后扫的赢（既有 M6a 用例钉住的行为，见 TDD §D4）
        saveRule(ops, DEFAULT_PRIORITY, 30, "OVERWRITE", "\"DEVICE\",\"PHONE\"");
        String mover = login("13000530032");
        String moverNo = userNoOf(mover);
        report(mover, storeA, null, null);
        JsonNode replaced = report(mover, storeB, null, null);
        assertThat(replaced.get("merchantNo").asString())
                .as("OVERWRITE 下后扫的那家胜出").isEqualTo(storeB);
        JsonNode trace = traceOf(ops, moverNo);
        assertThat(trace.get("decision").asString()).isEqualTo("REPLACED");
        assertThat(trace.get("prevRef").asString())
                .as("被覆盖的那一家要留在痕里 —— 这正是 B1 说的争议场景").isEqualTo(storeA);
    }

    @Test
    @DisplayName("★ 窗口期真的写进归属：改成 1 天，新归属的到期时刻跟着变")
    void windowDaysDrivesExpiry() throws Exception {
        String ops = opsLogin();
        String store = merchantNoOf(merchant("12600530030", "增长·窗口期测试店"));

        saveRule(ops, DEFAULT_PRIORITY, 30, "OVERWRITE", "\"DEVICE\",\"PHONE\"");
        long thirty = report(login("13000530041"), store, null, null).get("expireAt").asLong();

        saveRule(ops, DEFAULT_PRIORITY, 1, "OVERWRITE", "\"DEVICE\",\"PHONE\"");
        long one = report(login("13000530042"), store, null, null).get("expireAt").asLong();

        long now = System.currentTimeMillis();
        assertThat(thirty - now).isGreaterThan(20L * 86_400_000L);
        assertThat(one - now)
                .as("窗口期改成 1 天，到期时刻必须在两天以内 —— 否则那个数字只是存着好看")
                .isLessThan(2L * 86_400_000L);
    }

    // ---------------------------------------------------------------- 拒绝路径（4.3）

    @Test
    @DisplayName("归因规则的四条闸：优先级非全序 / 窗口越界 / 策略非法 / 新客因子为空")
    void attributionRuleRejections() throws Exception {
        String ops = opsLogin();
        // 半个优先级表在冲突时会随机裁决 —— 少一个来源就有一种来源无从裁决
        assertThat(codeOf(saveRuleRaw(ops, "\"STORE_CODE\",\"INVITER\"", 30, "OVERWRITE",
                "\"DEVICE\""))).isEqualTo(40006);
        assertThat(codeOf(saveRuleRaw(ops, "\"STORE_CODE\",\"STORE_CODE\",\"INVITER\"", 30,
                "OVERWRITE", "\"DEVICE\""))).isEqualTo(40006);
        // 0 天 = 悄悄关掉归因，全平台订单变成平台客流，商家佣金翻倍而没人收到通知
        assertThat(codeOf(saveRuleRaw(ops, DEFAULT_PRIORITY, 0, "OVERWRITE", "\"DEVICE\"")))
                .isEqualTo(40007);
        assertThat(codeOf(saveRuleRaw(ops, DEFAULT_PRIORITY, 91, "OVERWRITE", "\"DEVICE\"")))
                .isEqualTo(40007);
        assertThat(codeOf(saveRuleRaw(ops, DEFAULT_PRIORITY, 30, "RANDOM", "\"DEVICE\"")))
                .isNotEqualTo(0);
        // 一个因子都不选 = 所有人都是新客，新人券会被无限领
        assertThat(codeOf(saveRuleRaw(ops, DEFAULT_PRIORITY, 30, "OVERWRITE", "")))
                .isEqualTo(40008);
    }

    // ---------------------------------------------------------------- 裂变活动（9.2.1）

    @Test
    @DisplayName("★ 裂变活动：奖励只能是券 · 券模板必须存在 · 两边都 0 张被拒 · 启停往返")
    void fissionCampaignLifecycle() throws Exception {
        String ops = opsLogin();
        String couponNo = createCoupon(ops, "裂变奖励券");

        // 券模板不存在 → 保存就拒，而不是发奖那一刻才失败
        assertThat(codeOf(saveFissionRaw(ops, null, "指向不存在的券", "CP-NOPE", 1, 1)))
                .isNotEqualTo(0);
        // 两边都 0 张 = 一张券都不发，这个活动存在的意义是什么
        assertThat(codeOf(saveFissionRaw(ops, null, "零张活动", couponNo, 0, 0))).isEqualTo(10400);
        assertThat(codeOf(saveFissionRaw(ops, null, "负数活动", couponNo, -1, 2))).isEqualTo(10400);
        assertThat(codeOf(saveFissionRaw(ops, null, "", couponNo, 1, 1))).isEqualTo(10400);

        JsonNode created = data(saveFissionRaw(ops, null, "老带新·首发", couponNo, 2, 1));
        String fissionNo = created.get("fissionNo").asString();
        // 奖励类型不从入参取 —— 端上传别的值也只能是券（ADR-004：不用现金买增长）
        assertThat(created.get("rewardType").asString()).isEqualTo("COUPON");
        assertThat(created.get("enabled").asBoolean()).as("新建默认不启用").isFalse();
        assertThat(created.get("invitedCount").asInt()).isZero();

        // 改：同一个 fissionNo 是保存不是新建
        JsonNode edited = data(saveFissionRaw(ops, fissionNo, "老带新·改名", couponNo, 3, 1));
        assertThat(edited.get("fissionNo").asString()).isEqualTo(fissionNo);
        assertThat(edited.get("inviterCount").asInt()).isEqualTo(3);

        // 启停往返，且列表读得回来
        assertThat(data(setFissionEnabled(ops, fissionNo, true)).get("enabled").asBoolean()).isTrue();
        assertThat(fissionOf(ops, fissionNo, true)).as("启用后要出现在「只看启用」里").isNotNull();
        assertThat(data(setFissionEnabled(ops, fissionNo, false)).get("enabled").asBoolean()).isFalse();
        assertThat(fissionOf(ops, fissionNo, true)).as("停用后不该再出现在「只看启用」里").isNull();
        assertThat(fissionOf(ops, fissionNo, false)).isNotNull();

        // 停用的券不能拿去启用活动 —— 否则发奖那一刻才失败，而人已经被邀请来了
        mvc().perform(post("/ops/coupons/" + couponNo + "/status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"reason\":\"活动下线\"}"))
                .andExpect(jsonPath("$.data.status").value("PAUSED"));
        assertThat(codeOf(setFissionEnabled(ops, fissionNo, true))).isNotEqualTo(0);
    }

    // ---------------------------------------------------------------- 装配

    private JsonNode rule(String ops) throws Exception {
        String body = mvc().perform(get("/ops/attribution-rule").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode saveRule(String ops, String priority, int windowDays, String policy,
                              String factors) throws Exception {
        return data(saveRuleRaw(ops, priority, windowDays, policy, factors));
    }

    private String saveRuleRaw(String ops, String priority, int windowDays, String policy,
                               String factors) throws Exception {
        return mvc().perform(post("/ops/attribution-rule").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":[" + priority + "],\"windowDays\":" + windowDays
                                + ",\"conflictPolicy\":\"" + policy + "\",\"newUserFactors\":["
                                + factors + "]}"))
                .andReturn().getResponse().getContentAsString();
    }

    /** 这个人最新的一条归因痕；没有就 null。 */
    private JsonNode traceOf(String ops, String userNo) throws Exception {
        String body = mvc().perform(get("/ops/attribution-traces").param("userNo", userNo)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode records = json.readTree(body).get("data").get("records");
        return records.isEmpty() ? null : records.get(0);
    }

    private JsonNode report(String buyer, String merchantNo, String inviterNo, String channel)
            throws Exception {
        String body = mvc().perform(post("/mp/attribution/report")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":" + nullable(merchantNo)
                                + ",\"inviterNo\":" + nullable(inviterNo)
                                + ",\"channel\":" + nullable(channel) + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    /** 用一个全新的买家报同一份线索，返回判定出来的来源 —— 优先级的直接观测点。 */
    private String reportSource(String buyer, String merchantNo, String inviterNo) throws Exception {
        return report(buyer, merchantNo, inviterNo, null).get("source").asString();
    }

    private static String field(String name, String value) {
        return value == null ? "" : "\"" + name + "\":\"" + value + "\",";
    }

    /** JSON 里的可空串：null 要发成 {@code null} 而不是 {@code "null"}。 */
    private static String nullable(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String createCoupon(String ops, String title) throws Exception {
        String body = mvc().perform(post("/ops/coupons").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"type\":\"FULL_CUT\",\"faceMinor\":300,"
                                + "\"totalCount\":100,\"budgetMinor\":30000,\"startAt\":"
                                + System.currentTimeMillis() + ",\"endAt\":"
                                + (System.currentTimeMillis() + 30L * 86_400_000L) + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("couponNo").asString();
    }

    private String saveFissionRaw(String ops, String fissionNo, String name, String couponNo,
                                  int inviter, int invitee) throws Exception {
        return mvc().perform(post("/ops/fission-campaigns").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + field("fissionNo", fissionNo) + "\"name\":\"" + name
                                + "\",\"couponNo\":\"" + couponNo + "\",\"inviterCount\":" + inviter
                                + ",\"inviteeCount\":" + invitee + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String setFissionEnabled(String ops, String fissionNo, boolean enabled) throws Exception {
        return mvc().perform(post("/ops/fission-campaigns/" + fissionNo + "/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":" + enabled + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode fissionOf(String ops, String fissionNo, boolean enabledOnly) throws Exception {
        String body = mvc().perform(get("/ops/fission-campaigns")
                        .param("enabledOnly", String.valueOf(enabledOnly)).param("size", "100")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (fissionNo.equals(r.get("fissionNo").asString())) {
                return r;
            }
        }
        return null;
    }

    /** 下单 + 付款。**不打回调的话单停在 WAIT_PAY**，trafficSource 那一列就没有意义。 */
    private void buyAndPay(String buyer, String goodsNo, String skuNo, String idem) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idem
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
    }

    private JsonNode latestOrder(String buyer) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(body).get("data").get("records").get(0);
        /*
         * 端上词表：付款之后 C 端看到的是 PAID（库里是 WAIT_FULFILL）。
         * 断这一条是为了钉住「回调真的打上了」—— 没打的话这里是 WAIT_PAY，
         * 而 trafficSource 那一列在未支付单上没有意义。
         */
        assertThat(row.get("status").asString())
                .as("没打支付回调的话单会停在 WAIT_PAY").isEqualTo("PAID");
        return row;
    }

    private String storeCodeOf(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        return m.getStoreCode();
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"增长归因测试品\",\"type\":\"NORMAL\","
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

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
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
        return login(phone);
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private int codeOf(String body) {
        return json.readTree(body).get("code").asInt();
    }

    private JsonNode data(String body) {
        JsonNode root = json.readTree(body);
        if (root.get("code").asInt() != 0) {
            throw new AssertionError("期望成功，实际：" + body);
        }
        return root.get("data");
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
