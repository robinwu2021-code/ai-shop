package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 商家端支付（TDD-支付服务-I5接入层 S8，F40 F42 F43 F44 F45）：代客收款 → 顾客经收银台付 → 本店流水 → 发起退款；
 * 本店静态码；授权第三方。商家只对自己的门店、自己的钱。
 */
// 支付服务 I1–I5 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BizPayFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private PayMerchantBindingService bindings;
    @Autowired private SettlePort settle;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 一家新商家：入驻审核通过 → B 端令牌 → 默认店挂上 TEST 收款商户（平台服务商号下的子商户） */
    private record Shop(String token, String storeNo, Long storeId) {
    }

    private Shop shop() throws Exception {
        String phone = "136" + String.format("%08d", (long) (Math.random() * 1e8));
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"收款测试" + id() + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"CM001\"]}"))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String storeNo = null;
        for (JsonNode s : data(call(token, null, get("/biz/store/list")))) {
            if (s.get("isDefault").asBoolean()) {
                storeNo = s.get("storeNo").asString();
            }
        }
        Long storeId = jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, storeNo);
        String pm = "PM-BZ-" + id();
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, sec_key_version, status,"
                + " invoice_capable, priority, created_at, updated_at) VALUES (?, 'PLATFORM', NULL, 'TEST', 'CN', 'CNY',"
                + " 'MERCHANT_OWN', 'AGGREGATED', ?, '', 0, 'ACTIVE', 0, 1000, NOW(), NOW())", "P" + pm, "PLATFORM-" + pm);
        Long parent = jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, "P" + pm);
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, parent_pay_merchant_id, sec_key_version,"
                + " status, invoice_capable, priority, created_at, updated_at) VALUES (?, 'STORE', ?, 'TEST', 'CN', 'CNY',"
                + " 'PLATFORM_SUB', 'AGGREGATED', ?, ?, ?, 0, 'ACTIVE', 0, 100, NOW(), NOW())",
                pm, storeId, "PLATFORM-" + pm, "SUB-" + pm, parent);
        Long merchantId = jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, pm);
        jdbc.update("INSERT INTO pay_merchant_product (pay_merchant_id, pay_channel_product_id, status, source,"
                + " quota_limit_minor, quota_used_minor, created_at, updated_at) SELECT ?, id, 'OPEN', 'OPS', 0, 0,"
                + " NOW(), NOW() FROM pay_channel_product WHERE product_code='TEST_ONLINE'", merchantId);
        bindings.bind(storeId, "TEST", merchantId, "OP-BZ");
        return new Shop(token, storeNo, storeId);
    }

    private MockHttpServletResponse call(String token, String storeNo, MockHttpServletRequestBuilder b) throws Exception {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (storeNo != null) {
            b.header("X-Store-No", storeNo);
        }
        return mvc().perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse send(Shop s, MockHttpServletRequestBuilder b, String body) throws Exception {
        return call(s.token(), s.storeNo(), b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 统一信封 {code:0, data}；业务拒绝是 HTTP 200 + 非零码 */
    private JsonNode data(MockHttpServletResponse r) throws Exception {
        JsonNode n = json.readTree(r.getContentAsString());
        assertThat(n.get("code").asInt()).as(r.getContentAsString()).isZero();
        return n.get("data");
    }

    private int code(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString()).get("code").asInt();
    }

    @Test
    @DisplayName("★★★ 没有商家令牌一律 401，且请求留痕（入口 BIZ_API、鉴权失败）")
    void unauthenticatedIsRejectedAndLogged() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE entry='BIZ_API'", Long.class);
        MockHttpServletResponse r = call(null, null, post("/biz/pay/collection")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":100}"));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE entry='BIZ_API'"
                + " AND outcome='REJECTED_AUTH'", Long.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE entry='BIZ_API'", Long.class))
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("★★★ 代客收款 → 顾客经收银台付 → 本店流水见到 → 商家发起退款（操作员记在退款单上）")
    void collectPayStatementRefund() throws Exception {
        Shop s = shop();
        JsonNode issued = data(send(s, post("/biz/pay/collection"),
                "{\"amountMinor\":2600,\"subject\":\"代客收款\",\"expireMinutes\":10}"));
        String no = issued.get("payOrderNo").asString();
        String token = issued.get("token").asString();
        assertThat(issued.get("checkoutUrl").asString()).contains("/c/" + token);

        JsonNode view = json.readTree(mvc().perform(get("/checkout/v1/sessions/" + token)).andReturn().getResponse()
                .getContentAsString()).get("data");
        assertThat(view.get("amountMinor").asLong()).isEqualTo(2600);
        assertThat(view.get("storeName").asString()).isNotBlank();

        JsonNode started = json.readTree(mvc().perform(post("/checkout/v1/sessions/" + token + "/pay")
                .contentType(MediaType.APPLICATION_JSON).content("{\"payMethod\":\"TEST_PAY\"}")).andReturn().getResponse()
                .getContentAsString()).get("data");
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", "T-" + id(),
                2600, System.currentTimeMillis()));

        JsonNode day = data(call(s.token(), s.storeNo(), get("/biz/pay/statement")
                .param("day", LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString())));
        assertThat(day.get("paidCount").asInt()).isEqualTo(1);
        assertThat(day.get("paidMinor").asLong()).isEqualTo(2600);
        assertThat(day.get("items").toString()).contains(no);

        JsonNode refund = data(send(s, post("/biz/pay/refund"),
                "{\"payOrderNo\":\"" + no + "\",\"amountMinor\":600,\"reason\":\"少收一件\"}"));
        assertThat(refund.get("payRefundNo").asString()).isNotBlank();
        assertThat(data(call(s.token(), s.storeNo(), get("/biz/pay/refund"))).toString())
                .contains(refund.get("payRefundNo").asString());
        Map<String, Object> pr = jdbc.queryForMap("SELECT * FROM pay_refund WHERE pay_refund_no=?",
                refund.get("payRefundNo").asString());
        assertThat(pr.get("source_type")).isEqualTo("BIZ");
        assertThat((String) pr.get("requested_by")).as("退款单记得是哪个商家账号发起的").isNotBlank();
    }

    @Test
    @DisplayName("★★★ 商家只碰自己的钱：别家门店的支付单退不了、流水里也看不到")
    void cannotTouchAnotherShop() throws Exception {
        Shop a = shop();
        Shop b = shop();
        JsonNode issued = data(send(a, post("/biz/pay/collection"), "{\"amountMinor\":500}"));
        String no = issued.get("payOrderNo").asString();
        // 先让 A 的单真的付成 —— 没付的单本来就不进任何人的流水，那样断言「B 看不到」什么也没证明
        JsonNode started = json.readTree(mvc().perform(post("/checkout/v1/sessions/" + issued.get("token").asString() + "/pay")
                .contentType(MediaType.APPLICATION_JSON).content("{\"payMethod\":\"TEST_PAY\"}")).andReturn().getResponse()
                .getContentAsString()).get("data");
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", "T-" + id(),
                500, System.currentTimeMillis()));
        assertThat(data(call(a.token(), a.storeNo(), get("/biz/pay/statement"))).toString()).contains(no);
        int rejected = code(send(b, post("/biz/pay/refund"), "{\"payOrderNo\":\"" + no + "\",\"amountMinor\":100}"));
        assertThat(rejected).as("别家的单").isNotZero();
        assertThat(data(call(b.token(), b.storeNo(), get("/biz/pay/statement"))).toString()).doesNotContain(no);
    }

    @Test
    @DisplayName("★★★ 本店静态码：生成（码值随机）→ 顾客能扫 → 停用后扫不到")
    void staticCodes() throws Exception {
        Shop s = shop();
        JsonNode qr = data(send(s, post("/biz/pay/qr-code"),
                "{\"kind\":\"TABLE\",\"tableRef\":\"A3\",\"singleLimitMinor\":5000,\"dailyLimitMinor\":20000}"));
        String code = qr.get("qrCode").asString();
        assertThat(code).hasSizeGreaterThanOrEqualTo(22);
        assertThat(data(call(s.token(), s.storeNo(), get("/biz/pay/qr-code"))).toString()).contains(code);
        assertThat(mvc().perform(get("/checkout/v1/codes/" + code)).andReturn().getResponse().getStatus()).isEqualTo(200);

        data(call(s.token(), s.storeNo(), put("/biz/pay/qr-code/" + code + "/disabled")));
        assertThat(mvc().perform(get("/checkout/v1/codes/" + code)).andReturn().getResponse().getStatus()).isEqualTo(404);

        Shop other = shop();
        assertThat(code(call(other.token(), other.storeNo(), put("/biz/pay/qr-code/" + code + "/disabled"))))
                .as("别家的码停不了").isNotZero();
    }

    @Test
    @DisplayName("★★ 授权第三方代本店收款：按 AppId 授权、列出、撤销；撤销后状态即 REVOKED")
    void appGrants() throws Exception {
        Shop s = shop();
        String appNo = "APP-BZ-" + id();
        jdbc.update("INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps,"
                + " daily_refund_limit_minor, sandbox, created_at, updated_at) VALUES (?, '试点', 'PARTNER', 'ACTIVE',"
                + " 'pay:create,pay:read', 100, 0, 1, NOW(), NOW())", appNo);
        JsonNode g = data(send(s, post("/biz/pay/app-grant"), "{\"appNo\":\"" + appNo + "\",\"actions\":[\"COLLECT\",\"READ\"]}"));
        assertThat(g.get("actions").toString()).contains("COLLECT");
        assertThat(data(call(s.token(), s.storeNo(), get("/biz/pay/app-grant"))).toString()).contains(appNo);
        data(call(s.token(), s.storeNo(), delete("/biz/pay/app-grant/" + g.get("id").asLong())));
        assertThat(jdbc.queryForObject("SELECT status FROM pay_app_grant WHERE id=?", String.class, g.get("id").asLong()))
                .isEqualTo("REVOKED");
        assertThat(code(send(s, post("/biz/pay/app-grant"), "{\"appNo\":\"MALL\",\"actions\":[\"COLLECT\"]}")))
                .as("内部接入方不能被商家授权").isNotZero();
    }
}
