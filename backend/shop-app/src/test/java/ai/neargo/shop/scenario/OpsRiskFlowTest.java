package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.spi.risk.RiskEventPort;
import ai.neargo.shop.support.TestLogin;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端风控域（TDD-运营端风控域，P-16.2）。
 *
 * <p>这个类守的是「风控看起来在跑」与「风控真的在跑」之间的那条缝：
 * <ol>
 *   <li><b>事件由真实交易产生</b> —— 真买家真下单，走 Outbox 投递到风控消费者。
 *       造一条 {@code risk_event} 再断言「列表里有一行」，一个返回假数据的实现也能通过</li>
 *   <li><b>阈值真的在驱动识别</b> —— 出厂阈值是 10，改成 2 之后 <b>2 单就命中</b>；
 *       改回 10 之后同一个人再下一单不会再开单。阈值只是存住的话这两条都会红</li>
 *   <li><b>拉黑对被拉的人真的生效</b> —— 断 {@code RiskEventPort.blocked}，
 *       申诉通过之后它必须变回 false，而黑名单记录仍在（留痕不是删除）</li>
 *   <li><b>幂等</b> —— Outbox 是 at-least-once，同一张单重投只该计一次命中。
 *       没有这一条，投递器重启一次就能把正常用户送进黑名单</li>
 * </ol>
 *
 * <p>手机号段 {@code 126005xxxxx}（商户）/ {@code 130005xxxxx}（买家），尾号 1xxxx 段归本类。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsRiskFlowTest {

    private static final String FAKE_ORDER = "FAKE_ORDER";
    private static final String ABNORMAL_FISSION = "ABNORMAL_FISSION";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private RiskEventPort riskEventPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /**
     * 规则是**全局单例**（一类一行），本类会把阈值改小以便用两三单就命中。
     * 跑完必须还原，否则整套测试后半程每个下过两单的买家都会被开一张刷单事件 ——
     * 那不会让别的用例变红，但会在库里堆一堆假事件，把真问题淹掉。
     */
    @AfterEach
    void restoreFactoryThresholds() throws Exception {
        String ops = opsLogin();
        saveRule(ops, FAKE_ORDER, 10, false);
        saveRule(ops, ABNORMAL_FISSION, 5, false);
    }

    // ---------------------------------------------------------------- 真链路：下单 → 事件 → 处置 → 拉黑

    @Test
    @DisplayName("★ 真实下单命中刷单阈值 → 裁决 → 拉黑 → 申诉解禁：每一步都对被拉的人生效")
    void fakeOrderToBlacklistJourney() throws Exception {
        String ops = opsLogin();
        String biz = merchant("12600510001", "风控·刷单识别店");
        String goodsNo = listedGoods(biz, 50);
        String skuNo = firstSku(goodsNo);

        // 出厂默认值必须是读时自愈补出来的那一份 —— 迁移里的 INSERT 进不了测试库，
        // 这一行红了说明「运营第一次打开拦截规则页是空列表」
        assertThat(ruleOf(ops, FAKE_ORDER).get("threshold").asInt()).isEqualTo(10);

        // 阈值改成 2 并读回来（读写往返）
        assertThat(saveRule(ops, FAKE_ORDER, 2, true).get("threshold").asInt()).isEqualTo(2);
        JsonNode saved = ruleOf(ops, FAKE_ORDER);
        assertThat(saved.get("threshold").asInt()).isEqualTo(2);
        assertThat(saved.get("autoBlock").asBoolean()).isTrue();

        String buyer = login("13000510011");
        String userNo = userNoOf(buyer);

        /*
         * 第一单：还没到阈值。
         * 这一步不是废话 —— 它证明后面那张事件是**第二单**带出来的，
         * 而不是「只要下单就开一张」。
         */
        String order1 = buy(buyer, goodsNo, skuNo, "risk-fo-1");
        drainOutbox();
        assertThat(eventOf(ops, userNo)).as("阈值 2，第一单不该开事件").isNull();

        String order2 = buy(buyer, goodsNo, skuNo, "risk-fo-2");
        drainOutbox();

        JsonNode event = eventOf(ops, userNo);
        assertThat(event).as("阈值 2，第二单必须开出刷单事件").isNotNull();
        assertThat(event.get("type").asString()).isEqualTo(FAKE_ORDER);
        assertThat(event.get("subjectType").asString()).isEqualTo("USER");
        assertThat(event.get("status").asString()).isEqualTo("PENDING");
        /*
         * 证据单号必须是**真的那两张单**。
         * 只断「refs 有两条」的话，一个把订单号写成 UUID 的实现照样通过 ——
         * 而运营点进去查不到单时，第一反应是「这单被删了」。
         */
        assertThat(refs(event)).containsExactlyInAnyOrder(order1, order2);
        assertThat(event.get("signals").get(0).asString()).contains("2 次命中");

        String eventNo = event.get("eventNo").asString();

        // 排除也要写理由：空结论被拒（60003）
        assertThat(codeOf(decide(ops, eventNo, false, "  "))).isEqualTo(60003);

        // 确认 → 状态与结论落库，列表里读得回来
        JsonNode decided = data(decide(ops, eventNo, true, "同一收货码 2 单，判定刷单"));
        assertThat(decided.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(decided.get("verdict").asString()).isEqualTo("同一收货码 2 单，判定刷单");
        assertThat(eventByNo(ops, eventNo).get("status").asString()).isEqualTo("CONFIRMED");

        // 处置过的不能再处置（60002）—— 否则两个运营会各写一份结论，最后谁也不知道按哪份处理的
        assertThat(codeOf(decide(ops, eventNo, false, "我觉得不是"))).isEqualTo(60002);

        // ---- 拉黑：对这个人真的生效
        assertThat(riskEventPort.blocked("USER", userNo)).isFalse();
        JsonNode black = data(addBlacklist(ops, "USER", userNo, "刷单确认", plusDays(7)));
        String blackNo = black.get("blackNo").asString();
        assertThat(black.get("active").asBoolean()).isTrue();
        assertThat(black.get("appealStatus").asString()).isEqualTo("NONE");
        assertThat(riskEventPort.blocked("USER", userNo))
                .as("拉黑之后 blocked 必须为真 —— 只写一行记录、判定不认它，等于没拉黑")
                .isTrue();

        // 还没申诉就裁决 → 60007（结构上不可达的端点在这里被挡住）
        assertThat(codeOf(decideAppeal(ops, blackNo, true, "行吧"))).isEqualTo(60007);

        // ---- C 端申诉（本域自建的那条，没有它 decideAppeal 永远等不到 PENDING）
        assertThat(data(appeal(buyer, "两单是给爸妈各买了一份")).get("appealStatus").asString())
                .isEqualTo("PENDING");

        // 裁决也要写结论
        assertThat(codeOf(decideAppeal(ops, blackNo, true, ""))).isEqualTo(60003);

        JsonNode upheld = data(decideAppeal(ops, blackNo, true, "核实属实，解除"));
        assertThat(upheld.get("appealStatus").asString()).isEqualTo("UPHELD");
        assertThat(upheld.get("active").asBoolean()).isFalse();
        assertThat(riskEventPort.blocked("USER", userNo))
                .as("申诉通过之后必须真的解除").isFalse();

        // **记录保留** —— 留痕不是删除：不筛生效中时仍然查得到这一条
        assertThat(blacklistOf(ops, userNo, false)).isNotNull();
        assertThat(blacklistOf(ops, userNo, true))
                .as("解除之后不该再出现在「只看生效中」里").isNull();

        /*
         * 阈值改回 10 之后再下一单：不该再开新事件。
         * 上一张已经处置完（dedup_key 让位），所以「有开着的单所以没新增」这条解释不成立 ——
         * 唯一的解释是阈值真的被读了。
         */
        saveRule(ops, FAKE_ORDER, 10, false);
        buy(buyer, goodsNo, skuNo, "risk-fo-3");
        drainOutbox();
        assertThat(eventOf(ops, userNo))
                .as("阈值调回 10，3 单不该再开新的待处置事件").isNull();
    }

    // ---------------------------------------------------------------- 异常裂变 + 幂等

    @Test
    @DisplayName("★ 同设备命中按阈值开单，同一证据单号重投只计一次（Outbox at-least-once）")
    void abnormalFissionThresholdAndIdempotency() throws Exception {
        String ops = opsLogin();
        String device = "DEV-RISK-A1";
        assertThat(saveRule(ops, ABNORMAL_FISSION, 3, false).get("threshold").asInt()).isEqualTo(3);

        /*
         * 走 {@code RiskEventPort} 而不是造表：这正是增长域归因判定时要调的那个出口
         * （TDD §D2 —— 方向是「生产方推给风控」）。增长域本身还没落地，
         * 但这条契约今天就必须是活的，否则接的人会先发现它是坏的。
         */
        assertThat(riskEventPort.hit(ABNORMAL_FISSION, "DEVICE", device, "设备 " + device,
                "AT-RISK-A1", "同设备带入用户 U1")).isFalse();
        assertThat(riskEventPort.hit(ABNORMAL_FISSION, "DEVICE", device, "设备 " + device,
                "AT-RISK-A2", "同设备带入用户 U2")).isFalse();
        assertThat(eventOf(ops, device)).as("2 < 阈值 3，不该开单").isNull();

        assertThat(riskEventPort.hit(ABNORMAL_FISSION, "DEVICE", device, "设备 " + device,
                "AT-RISK-A3", "同设备带入用户 U3")).isTrue();
        JsonNode event = eventOf(ops, device);
        assertThat(event).isNotNull();
        assertThat(event.get("type").asString()).isEqualTo(ABNORMAL_FISSION);
        assertThat(event.get("subjectType").asString()).isEqualTo("DEVICE");
        assertThat(refs(event)).containsExactlyInAnyOrder("AT-RISK-A1", "AT-RISK-A2", "AT-RISK-A3");
        assertThat(event.get("signals").get(0).asString()).contains("3 次命中");

        /*
         * 同一条证据重投。**这一条是幂等的全部意义**：
         * 重投把命中数刷成 4 的话，一次投递器重启就能把一个正常用户送进黑名单，
         * 而事件上写着「4 次命中」，看起来完全合理。
         */
        riskEventPort.hit(ABNORMAL_FISSION, "DEVICE", device, "设备 " + device,
                "AT-RISK-A3", "同设备带入用户 U3（重投）");
        JsonNode again = eventOf(ops, device);
        assertThat(refs(again)).hasSize(3);
        assertThat(again.get("signals").get(0).asString()).contains("3 次命中");

        // 空主体 / 空证据一律不记 —— 上游少传一个字段不该在风控这边变成一条「主体为空」的事件
        assertThat(riskEventPort.hit(ABNORMAL_FISSION, "DEVICE", null, null, "AT-RISK-A9", "x"))
                .isFalse();
        assertThat(riskEventPort.hit(ABNORMAL_FISSION, "DEVICE", device, null, null, "x"))
                .isFalse();
    }

    // ---------------------------------------------------------------- 拒绝路径

    @Test
    @DisplayName("拉黑的四条闸：无原因 / 无到期 / 到期已过 / 重复拉黑")
    void blacklistRejections() throws Exception {
        String ops = opsLogin();
        String subject = "CU-RISK-B1";

        assertThat(codeOf(addBlacklist(ops, "USER", subject, "", plusDays(3)))).isEqualTo(60004);
        assertThat(codeOf(addBlacklist(ops, "USER", subject, "薅羊毛", null))).isEqualTo(60005);
        // 无期限拉黑没有申诉出口 —— 过去的时间同样不行，那等于「拉了个寂寞」
        assertThat(codeOf(addBlacklist(ops, "USER", subject, "薅羊毛", plusDays(-1)))).isEqualTo(60005);
        assertThat(codeOf(addBlacklist(ops, "USER", "", "薅羊毛", plusDays(3)))).isEqualTo(10400);

        assertThat(codeOf(addBlacklist(ops, "USER", subject, "薅羊毛", plusDays(3)))).isZero();
        // 同一主体已有生效中的记录 → 60006（两条生效记录会让「到期时间」变成猜哪一条）
        assertThat(codeOf(addBlacklist(ops, "USER", subject, "又薅了一次", plusDays(5)))).isEqualTo(60006);
    }

    @Test
    @DisplayName("★ 拉黑到期：判定放行、列表不再算生效中、同一个人还能再拉一次")
    void blacklistExpiryIsOneTruth() throws Exception {
        String ops = opsLogin();
        String subject = "CU-RISK-B2";

        // 到期时间只给 2 秒 —— 到期是这条用例的被测事实，只能真的等它过去
        String soon = Instant.now().plusSeconds(2).toString();
        assertThat(data(addBlacklist(ops, "USER", subject, "短期观察", soon))
                .get("active").asBoolean()).isTrue();
        assertThat(riskEventPort.blocked("USER", subject)).isTrue();

        Thread.sleep(2500);

        /*
         * 到期之后三处必须说同一件事。
         * 此前只有 blocked() 带了到期判断，另外两处没带 —— 于是
         * <b>那个人既不再被拦，也再也拉黑不上</b>，而列表上他还写着「生效中」。
         */
        assertThat(riskEventPort.blocked("USER", subject)).isFalse();
        assertThat(blacklistOf(ops, subject, true))
                .as("到期的记录不该还出现在「只看生效中」里").isNull();
        assertThat(blacklistOf(ops, subject, false).get("active").asBoolean())
                .as("到期即不生效，列上的值不是唯一口径").isFalse();

        // 再拉一次：这才是「拉黑有期限」的完整语义 —— 到期之后可以重新处置
        assertThat(codeOf(addBlacklist(ops, "USER", subject, "又犯了", plusDays(7))))
                .as("上一条已到期，不该再报「已在生效中的黑名单里」").isZero();
        assertThat(riskEventPort.blocked("USER", subject)).isTrue();
    }

    @Test
    @DisplayName("阈值 0 与未知类型被拒 —— 0 等于全量拦截，而页面上它只是一个普通数字")
    void ruleRejections() throws Exception {
        String ops = opsLogin();
        assertThat(codeOf(saveRuleRaw(ops, FAKE_ORDER, 0, false))).isEqualTo(60008);
        assertThat(codeOf(saveRuleRaw(ops, FAKE_ORDER, -3, false))).isEqualTo(60008);
        assertThat(codeOf(saveRuleRaw(ops, "NOT_A_RISK_TYPE", 5, false))).isNotEqualTo(0);

        // 三条规则一条不少：读时自愈补的是全集，不是「用到哪条补哪条」
        String body = mvc().perform(get("/ops/risk-rules").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode rules = json.readTree(body).get("data");
        assertThat(rules).hasSize(3);
    }

    // ---------------------------------------------------------------- 装配

    private void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    /** @return 主单号（= {@code ORDER_CREATED} 的 aggregateId，也就是事件里的证据单号） */
    private String buy(String buyer, String goodsNo, String skuNo, String idem) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("orderNo").asString();
    }

    /** 该主体当前**待处置**的事件；没有就 null。 */
    private JsonNode eventOf(String ops, String subject) throws Exception {
        String body = mvc().perform(get("/ops/risk-events")
                        .param("status", "PENDING").param("keyword", subject)
                        .param("size", "200")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (subject.equals(r.get("subject").asString())) {
                return r;
            }
        }
        return null;
    }

    private JsonNode eventByNo(String ops, String eventNo) throws Exception {
        String body = mvc().perform(get("/ops/risk-events").param("keyword", eventNo)
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (eventNo.equals(r.get("eventNo").asString())) {
                return r;
            }
        }
        throw new AssertionError("查不到事件 " + eventNo);
    }

    private JsonNode blacklistOf(String ops, String subject, boolean activeOnly) throws Exception {
        String body = mvc().perform(get("/ops/blacklists").param("keyword", subject)
                        .param("activeOnly", activeOnly ? "1" : "0")
                        .param("size", "200")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (subject.equals(r.get("subject").asString())) {
                return r;
            }
        }
        return null;
    }

    private String decide(String ops, String eventNo, boolean confirmed, String verdict)
            throws Exception {
        return mvc().perform(post("/ops/risk-events/" + eventNo + "/decide")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":" + confirmed + ",\"verdict\":\"" + verdict + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String addBlacklist(String ops, String subjectType, String subject, String reason,
                                String until) throws Exception {
        return mvc().perform(post("/ops/blacklists").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"" + subjectType + "\",\"subject\":\"" + subject
                                + "\",\"reason\":\"" + reason + "\","
                                + "\"until\":" + (until == null ? "null" : "\"" + until + "\"") + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String decideAppeal(String ops, String blackNo, boolean accept, String verdict)
            throws Exception {
        return mvc().perform(post("/ops/blacklists/" + blackNo + "/appeal")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\":" + accept + ",\"verdict\":\"" + verdict + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String appeal(String buyer, String reason) throws Exception {
        return mvc().perform(post("/mp/risk/appeal").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode ruleOf(String ops, String type) throws Exception {
        String body = mvc().perform(get("/ops/risk-rules").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data")) {
            if (type.equals(r.get("type").asString())) {
                return r;
            }
        }
        throw new AssertionError("规则列表里没有 " + type);
    }

    private JsonNode saveRule(String ops, String type, int threshold, boolean autoBlock)
            throws Exception {
        return data(saveRuleRaw(ops, type, threshold, autoBlock));
    }

    private String saveRuleRaw(String ops, String type, int threshold, boolean autoBlock)
            throws Exception {
        return mvc().perform(post("/ops/risk-rules/" + type).header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":" + threshold + ",\"autoBlock\":" + autoBlock + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private static String plusDays(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private java.util.List<String> refs(JsonNode event) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode r : event.get("refs")) {
            out.add(r.asString());
        }
        return out;
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

    private String userNoOf(String buyer) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + buyer))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"风控测试品\",\"type\":\"NORMAL\","
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
