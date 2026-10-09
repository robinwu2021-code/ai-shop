package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 物流批 2a：发货即登记、推送入库（TDD-物流模块 AC1 / AC3 / AC4 / AC12）。
 *
 * <p>走真链路：商家发货 → outbox 投递 {@code SUB_ORDER_SHIPPED} → 登记骨架 → 物流内部事件 → 取快照；
 * 再用快递100 文档格式的推送打 {@code /callback/logistics/kuaidi100}。
 * 订阅总开关默认关，所以登记后停在 PENDING；推送前用 SQL 把它摆成「已在快递100 订上」。
 *
 * <p>号段 15973010xx：全仓库 grep 过没人用（撞号会让两个测试类拿到同一条记录，单独绿、全量红）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("物流：发货登记与推送入库")
class LogisticsRegistrationFlowTest {

    private static final String STUB_SECRET = "stub-secret";
    /** 与 application-testcfg.yml 的 shop.express.kuaidi100.salt 一致 */
    private static final String SALT = "test-kuaidi100-salt";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboxDispatcher dispatcher;

    @Test
    @DisplayName("★★★ 快递发货 → 运单登记 + 快照；订阅开关关着时停在 PENDING")
    void shippingRegistersWaybillWithSnapshot() throws Exception {
        Ctx c = prepare("15973010001", "物流登记测试店", "15973010002");
        ship(c, "STO-LGS-0001", "STO");
        drainOutbox();

        Map<String, Object> w = waybill(c.subOrderNo);
        assertThat(w).as("发货之后没有运单 —— 登记那一路没接上").isNotNull();
        assertThat(w.get("carrier")).isEqualTo("STO");
        assertThat(w.get("waybill_no")).isEqualTo("STO-LGS-0001");
        assertThat(w.get("sub_state")).as("订阅总开关默认关：停在待订阅").isEqualTo("PENDING");
        assertThat(w.get("region")).as("快照：收件地区").isEqualTo("浙江省 杭州市");
        assertThat(w.get("receiver_phone_last4")).as("快照：手机号后四位").isEqualTo("0099");
        assertThat(w.get("receiver_phone_enc")).as("测试没配 phone-key：明文永不落库，密文也不存").isNull();
        assertThat(w.get("entity_no")).as("快照：商家主体").isNotNull();
        assertThat(w.get("store_no")).as("快照：门店").isNotNull();
    }

    @Test
    @DisplayName("★★★ 快递100 推送：验签 → 落节点 → 签收（只写一次、发事件）；回执是原文不是信封")
    void kuaidi100PushAdvancesToDelivered() throws Exception {
        Ctx c = prepare("15973010003", "物流推送测试店", "15973010004");
        ship(c, "STO-LGS-0002", "STO");
        drainOutbox();
        subscribedAt(c.subOrderNo, "kuaidi100");
        long eventsBefore = count("select count(*) from sys_outbox where event_type = 'WAYBILL_SIGNED'");

        String param = signedPush("STO-LGS-0002");
        String body = mvc().perform(post("/callback/logistics/kuaidi100")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("param", param).param("sign", sign(param)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).as("回执被统一信封包了一层，快递100 认不出成功会重推")
                .isEqualTo("{\"result\":true,\"returnCode\":\"200\",\"message\":\"成功\"}");
        Map<String, Object> w = waybill(c.subOrderNo);
        assertThat(w.get("status")).isEqualTo("DELIVERED");
        assertThat(w.get("signed_at")).as("签收时间取签收节点的时间").isEqualTo(at("2026-10-09 10:00:00"));
        assertThat(w.get("picked_up_at")).as("揽收时间取揽收节点的时间").isEqualTo(at("2026-10-08 09:00:00"));
        assertThat(count("select count(*) from lgs_waybill_node where shipment_no = '" + w.get("shipment_no") + "'"))
                .isEqualTo(2);
        assertThat(count("select count(*) from sys_outbox where event_type = 'WAYBILL_SIGNED'"))
                .as("首次签收发一次事件").isEqualTo(eventsBefore + 1);

        // 快递100 每次推全量：同一条再来一次，节点不翻倍、事件不重发
        mvc().perform(post("/callback/logistics/kuaidi100")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("param", param).param("sign", sign(param))).andExpect(status().isOk());
        assertThat(count("select count(*) from lgs_waybill_node where shipment_no = '" + w.get("shipment_no") + "'"))
                .as("全量推送靠去重，节点不该翻倍").isEqualTo(2);
        assertThat(count("select count(*) from sys_outbox where event_type = 'WAYBILL_SIGNED'"))
                .as("签收事件只发一次").isEqualTo(eventsBefore + 1);
    }

    @Test
    @DisplayName("★★★ 物流页 GET /mp/order/{no}/trace：本人看得到；别人查同一张子单 10404（防 IDOR）")
    void tracePageIsOwnerOnly() throws Exception {
        Ctx c = prepare("15973010007", "物流页测试店", "15973010008");
        ship(c, "STO-LGS-0004", "STO");
        drainOutbox();

        String body = mvc().perform(get("/mp/order/" + c.subOrderNo + "/trace")
                        .header("Authorization", "Bearer " + c.buyerToken).header("X-Client", "H5"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var data = json.readTree(body).get("data");
        assertThat(data.get("waybillNo").asString()).isEqualTo("STO-LGS-0004");
        assertThat(data.get("carrier").asString()).isEqualTo("STO");
        assertThat(data.get("status").asString()).isEqualTo("CREATED");
        assertThat(data.get("displayMode").asString()).as("H5 里没有微信插件").isEqualTo("self-map");

        String stranger = login("15973010009");
        mvc().perform(get("/mp/order/" + c.subOrderNo + "/trace").header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("★★ 验签失败：照样回成功（重推也不会变对），但一个字都不入库")
    void badSignatureIsAckedButIgnored() throws Exception {
        Ctx c = prepare("15973010005", "物流验签测试店", "15973010006");
        ship(c, "STO-LGS-0003", "STO");
        drainOutbox();
        subscribedAt(c.subOrderNo, "kuaidi100");

        String param = signedPush("STO-LGS-0003");
        mvc().perform(post("/callback/logistics/kuaidi100")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("param", param).param("sign", "WRONG"))
                .andExpect(status().isOk());
        assertThat(waybill(c.subOrderNo).get("status")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("★ 渠道名不存在 → 回 result:false（写不出 404：全局异常处理把任何异常转成 200 + 10500）")
    void unknownChannelSaysFalse() throws Exception {
        String body = mvc().perform(post("/callback/logistics/no-such-channel")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("param", "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"result\":false").doesNotContain("\"code\"");
    }

    // ------------------------------------------------------------------ 辅助

    /** 快递100 文档格式（resultv2=4）：揽收 → 签收，倒序 */
    private String signedPush(String nu) {
        return "{\"status\":\"shutdown\",\"billstatus\":\"\",\"message\":\"\",\"autoCheck\":\"0\",\"comOld\":\"\",\"comNew\":\"\","
                + "\"lastResult\":{\"message\":\"ok\",\"state\":\"3\",\"status\":\"200\",\"condition\":\"F00\",\"ischeck\":\"1\","
                + "\"com\":\"shentong\",\"nu\":\"" + nu + "\",\"data\":["
                + "{\"context\":\"已签收，签收人：本人\",\"time\":\"2026-10-09 10:00:00\",\"ftime\":\"2026-10-09 10:00:00\","
                + "\"status\":\"签收\",\"statusCode\":\"301\",\"areaCode\":\"330100000000\",\"areaName\":\"浙江,杭州市\"},"
                + "{\"context\":\"快件已揽收\",\"time\":\"2026-10-08 09:00:00\",\"ftime\":\"2026-10-08 09:00:00\","
                + "\"status\":\"揽收\",\"statusCode\":\"103\",\"areaCode\":\"\",\"areaName\":\"\"}]}}";
    }

    private static String sign(String param) throws Exception {
        byte[] h = MessageDigest.getInstance("MD5").digest((param + SALT).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static long at(String ts) {
        return java.time.LocalDateTime.parse(ts.replace(' ', 'T'))
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    /** 摆成「已在这家渠道订上」—— 订阅开关在测试里关着，真订阅留给批 2b 的实测 */
    private void subscribedAt(String subOrderNo, String channel) {
        int n = jdbc.update("update lgs_waybill set sub_state = 'DONE', sub_channel = ? where biz_ref = ?",
                channel, subOrderNo);
        assertThat(n).as("子单 %s 没有运单", subOrderNo).isEqualTo(1);
    }

    private Map<String, Object> waybill(String subOrderNo) {
        var rows = jdbc.queryForList("select * from lgs_waybill where biz_ref = ?", subOrderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** 推到清空：一次只投 200 条，而队列是全量共用的（见 InventoryBizEndpointTest#drainOutbox） */
    private void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private record Ctx(String merchantToken, String subOrderNo, String buyerToken) {
    }

    private void ship(Ctx c, String expressNo, String company) throws Exception {
        mvc().perform(post("/biz/order/" + c.subOrderNo + "/ship")
                        .header("Authorization", "Bearer " + c.merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"" + expressNo + "\",\"expressCompany\":\"" + company + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Ctx prepare(String merchantPhone, String shopName, String buyerPhone) throws Exception {
        String user = login(merchantPhone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + shopName + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();
        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, merchantPhone);
        TestStoreCategory.open(mvc(), json, token, "CAT210");
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"物流测试商品\",\"subtitle\":\"测试\","
                                + "\"type\":\"NORMAL\",\"cover\":\"📦\",\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
        String ops = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit").header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        String buyer = login(buyerPhone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        String addressId = json.readTree(mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"买家\",\"phone\":\"15973010099\",\"province\":\"浙江省\","
                                + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\"文三路 1 号\","
                                + "\"isDefault\":true,\"tag\":\"家\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("addressId").asString();
        String body = "{\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + addressId + "\",\"items\":["
                + "{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}]}";
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                                + payOrderNo + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
        String list = mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var records = json.readTree(list).get("data").get("records");
        assertThat(records.size()).as("商家应当看得到刚下的单").isGreaterThan(0);
        return new Ctx(token, records.get(0).get("orderNo").asString(), buyer);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        return TestLogin.operator(mvc(), json, username, password);
    }
}
