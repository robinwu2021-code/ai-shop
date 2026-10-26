package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
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
 * 开放 API 主链（TDD-支付服务-I5接入层 S2–S4；安全规范 §2.3）：签名 v1 → 建单（幂等）→ 选方式发起支付（冻结路由）→ 查单 / 关单 → 退款。
 * <p>
 * 签名在本类里<b>独立实现一遍</b>（{@link #sign}），与服务端的 {@code SignatureCanonicalizer} 互不引用 ——
 * 它就是对外文档里的规则本身：两边算出同一个签名，规则才算对得上。沙箱 app 的密钥来自测试配置（testcfg）。
 */
// 支付服务 I1–I5 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class OpenApiFlowTest {

    static final String APP1 = "APP-OPEN-T1";
    static final String SECRET1 = "open-t1-secret-0123456789abcdef0123";
    static final String APP2 = "APP-OPEN-T2";
    static final String SECRET2 = "open-t2-secret-0123456789abcdef0123";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PayMerchantBindingService bindings;
    @Autowired private SettlePort settle;
    @Autowired private OrderRepairPort orderRepair;

    private MockMvc mvc;
    private String storeNo;
    private Long storeId;

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void world() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        app(APP1, "pay:create,pay:read,pay:refund", 5_000);
        app(APP2, "pay:create,pay:read", 0);
        String e = "E-OA-" + id();
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, funds_mode, created_at, updated_at)"
                + " VALUES (?, ?, 'CN', 'AGGREGATED', NOW(), NOW())", e, "开放 " + e);
        storeNo = "S-OA-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, created_at, updated_at)"
                + " VALUES (?, ?, ?, 1, NOW(), NOW())", e, storeNo, "开放门店 " + storeNo);
        storeId = jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, storeNo);
        String pm = "PM-OA-" + id();
        // 子商户必须挂在平台服务商号下（ck_pay_merchant_3）：先建平台号，再建门店的子商户
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, sec_key_version, status,"
                + " invoice_capable, priority, created_at, updated_at) VALUES (?, 'PLATFORM', NULL, 'TEST', 'CN', 'CNY',"
                + " 'MERCHANT_OWN', 'AGGREGATED', ?, '', 0, 'ACTIVE', 0, 1000, NOW(), NOW())",
                "P" + pm, "PLATFORM-" + pm);
        Long parentId = jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, "P" + pm);
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, parent_pay_merchant_id, sec_key_version,"
                + " status, invoice_capable, priority, created_at, updated_at) VALUES (?, 'STORE', ?, 'TEST', 'CN', 'CNY',"
                + " 'PLATFORM_SUB', 'AGGREGATED', ?, ?, ?, 0, 'ACTIVE', 0, 100, NOW(), NOW())",
                pm, storeId, "PLATFORM-" + pm, "SUB-" + pm, parentId);
        Long merchantId = jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, pm);
        jdbc.update("INSERT INTO pay_merchant_product (pay_merchant_id, pay_channel_product_id, status, source,"
                + " quota_limit_minor, quota_used_minor, created_at, updated_at) SELECT ?, id, 'OPEN', 'OPS', 0, 0,"
                + " NOW(), NOW() FROM pay_channel_product WHERE product_code='TEST_ONLINE'", merchantId);
        bindings.bind(storeId, "TEST", merchantId, "OP-OA");
        grant(APP1, storeId, "COLLECT,REFUND,READ");
        grant(APP2, storeId, "COLLECT,READ");
    }

    private void app(String appNo, String scopes, long dailyRefund) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pay_app WHERE app_no=?", Integer.class, appNo) == 0) {
            jdbc.update("INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps,"
                    + " daily_refund_limit_minor, sandbox, created_at, updated_at) VALUES (?, ?, 'PARTNER', 'ACTIVE', ?,"
                    + " 1000, ?, 1, NOW(), NOW())", appNo, "试点 " + appNo, scopes, dailyRefund);
        }
    }

    private void grant(String appNo, Long store, String actions) {
        jdbc.update("INSERT INTO pay_app_grant (pay_app_id, store_id, actions, status, granted_by, granted_at,"
                + " created_at, updated_at) SELECT id, ?, ?, 'ACTIVE', 'U-OA', NOW(), NOW(), NOW() FROM pay_app WHERE app_no=?",
                store, actions, appNo);
    }

    // ------------------------------------------------------------------ 签名（对外规则的独立实现）

    static String sign(String secret, String method, String path, Map<String, String> query, String appNo,
                       long ts, String nonce, String body) throws Exception {
        String canonicalQuery = new TreeMap<>(query).entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        String toSign = method + "\n" + path + "\n" + canonicalQuery + "\n" + appNo + "\n" + ts + "\n" + nonce + "\n"
                + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }

    private record Signed(String appNo, String secret, long ts, String nonce) {
    }

    private Signed fresh(String appNo, String secret) {
        return new Signed(appNo, secret, System.currentTimeMillis(), UUID.randomUUID().toString().replace("-", ""));
    }

    private MockHttpServletResponse call(String method, String path, Map<String, String> query, String body, Signed s,
                                         String signatureOverride) throws Exception {
        String sig = signatureOverride != null ? signatureOverride
                : sign(s.secret(), method, path, query, s.appNo(), s.ts(), s.nonce(), body);
        MockHttpServletRequestBuilder b = "GET".equals(method) ? get(path) : post(path);
        query.forEach(b::queryParam);
        b.contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-App-Id", s.appNo()).header("X-Timestamp", String.valueOf(s.ts()))
                .header("X-Nonce", s.nonce()).header("X-Signature", sig);
        return mvc.perform(b).andReturn().getResponse();
    }

    private JsonNode ok(MockHttpServletResponse r) throws Exception {
        JsonNode n = json.readTree(r.getContentAsString());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(n.get("requestId").asString()).isNotBlank();
        return n.get("data");
    }

    private String createBody(String outOrderNo, long amount) {
        return "{\"outOrderNo\":\"" + outOrderNo + "\",\"storeRef\":\"" + storeNo + "\",\"amountMinor\":" + amount
                + ",\"currency\":\"CNY\",\"subject\":\"开放 API 测试\",\"expireMinutes\":30}";
    }

    private JsonNode create(String appNo, String secret, String outOrderNo, long amount) throws Exception {
        return ok(call("POST", "/open/pay/v1/orders", Map.of(), createBody(outOrderNo, amount), fresh(appNo, secret), null));
    }

    private int countLog(String outcome) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE outcome=?", Integer.class, outcome);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("★★★ 签名建单：返回支付单号与托管收银台地址；同 outOrderNo 同内容幂等，内容不同 409")
    void signedCreateIsIdempotent() throws Exception {
        String out = "OUT-" + id();
        JsonNode first = create(APP1, SECRET1, out, 1990);
        assertThat(first.get("payOrderNo").asString()).isNotBlank();
        assertThat(first.get("status").asString()).isEqualTo("CREATED");
        assertThat(first.get("checkoutUrl").asString()).contains(first.get("payOrderNo").asString());
        assertThat(create(APP1, SECRET1, out, 1990).get("payOrderNo").asString())
                .isEqualTo(first.get("payOrderNo").asString());
        MockHttpServletResponse conflict = call("POST", "/open/pay/v1/orders", Map.of(), createBody(out, 2990),
                fresh(APP1, SECRET1), null);
        assertThat(conflict.getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("★★★ 鉴权失败一律在业务之前挡下：没签名 / 签错 / 时间窗外 / nonce 重放 / 不认识的 app → 401，记 REJECTED_AUTH，不建单")
    void authFailuresNeverReachBusiness() throws Exception {
        int before = countLog("REJECTED_AUTH");
        String out = "OUT-" + id();
        String body = createBody(out, 100);

        MockHttpServletResponse none = mvc.perform(post("/open/pay/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
        assertThat(none.getStatus()).isEqualTo(401);
        assertThat(call("POST", "/open/pay/v1/orders", Map.of(), body, fresh(APP1, SECRET1), "AAAA").getStatus())
                .isEqualTo(401);
        Signed stale = new Signed(APP1, SECRET1, System.currentTimeMillis() - 10 * 60_000L, "stale-" + id());
        assertThat(call("POST", "/open/pay/v1/orders", Map.of(), body, stale, null).getStatus()).isEqualTo(401);
        Signed once = fresh(APP1, SECRET1);
        assertThat(call("POST", "/open/pay/v1/orders", Map.of(), createBody("OUT-" + id(), 100), once, null).getStatus())
                .isEqualTo(200);
        assertThat(call("POST", "/open/pay/v1/orders", Map.of(), createBody("OUT-" + id(), 100), once, null).getStatus())
                .as("同一 nonce 再来一次 = 重放").isEqualTo(401);
        assertThat(call("POST", "/open/pay/v1/orders", Map.of(), body, fresh("APP-NOBODY", SECRET1), null).getStatus())
                .isEqualTo(401);
        assertThat(call("POST", "/open/pay/v1/orders", Map.of(), body, fresh(APP1, SECRET2), null).getStatus())
                .as("用别家密钥签").isEqualTo(401);

        assertThat(countLog("REJECTED_AUTH") - before).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE out_order_no=?", Integer.class, out))
                .as("没有一次失败的请求建出单来").isZero();
    }

    @Test
    @DisplayName("★★★ 没被门店授权的 app 不能替这家店建单（403）")
    void ungrantedStoreForbidden() throws Exception {
        jdbc.update("UPDATE pay_app_grant SET status='REVOKED' WHERE store_id=? AND pay_app_id="
                + "(SELECT id FROM pay_app WHERE app_no=?)", storeId, APP2);
        MockHttpServletResponse r = call("POST", "/open/pay/v1/orders", Map.of(), createBody("OUT-" + id(), 100),
                fresh(APP2, SECRET2), null);
        assertThat(r.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("★★★ 选方式发起支付：按门店绑定选路由并冻结到支付单与交易尝试上，返回渠道参数")
    void startPaymentFreezesRoute() throws Exception {
        String no = create(APP1, SECRET1, "OUT-" + id(), 1990).get("payOrderNo").asString();
        JsonNode started = ok(call("POST", "/open/pay/v1/orders/" + no + "/pay", Map.of(),
                "{\"payMethod\":\"TEST_PAY\"}", fresh(APP1, SECRET1), null));
        assertThat(started.get("payChannel").asString()).isEqualTo("TEST");
        assertThat(started.get("payParams").get("prepayId").asString()).startsWith("test_");

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM pay_order WHERE pay_order_no=?", no);
        assertThat(order).containsEntry("pay_channel", "TEST").containsEntry("pay_method", "TEST_PAY")
                .containsEntry("access_mode", "PLATFORM_SUB").containsEntry("funds_path", "AGGREGATED");
        assertThat(order.get("pay_merchant_id")).isNotNull();
        Map<String, Object> attempt = jdbc.queryForMap("SELECT * FROM stl_payment WHERE pay_order_id=?", order.get("id"));
        assertThat(attempt.get("pay_merchant_id")).isEqualTo(order.get("pay_merchant_id"));
        assertThat(attempt).containsEntry("funds_path", "AGGREGATED");
    }

    @Test
    @DisplayName("★★★ 查单：按号、按 outOrderNo 都能查；别的 app 查不到（不承认存在）")
    void queryIsScopedToTheApp() throws Exception {
        String out = "OUT-" + id();
        String no = create(APP1, SECRET1, out, 500).get("payOrderNo").asString();
        assertThat(ok(call("GET", "/open/pay/v1/orders/" + no, Map.of(), "", fresh(APP1, SECRET1), null))
                .get("outOrderNo").asString()).isEqualTo(out);
        assertThat(ok(call("GET", "/open/pay/v1/orders", Map.of("outOrderNo", out), "", fresh(APP1, SECRET1), null))
                .get("payOrderNo").asString()).isEqualTo(no);
        assertThat(call("GET", "/open/pay/v1/orders/" + no, Map.of(), "", fresh(APP2, SECRET2), null).getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("★★★ 退款：要 pay:refund + 门店授权含 REFUND + 日限额；超额拒绝；没有退款 scope 的 app 403")
    void refundRules() throws Exception {
        String no = create(APP1, SECRET1, "OUT-" + id(), 3000).get("payOrderNo").asString();
        JsonNode started = ok(call("POST", "/open/pay/v1/orders/" + no + "/pay", Map.of(), "{\"payMethod\":\"TEST_PAY\"}",
                fresh(APP1, SECRET1), null));
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST",
                "T-" + id(), 3000, System.currentTimeMillis()));

        JsonNode refund = ok(call("POST", "/open/pay/v1/orders/" + no + "/refunds", Map.of(),
                "{\"outRefundNo\":\"RF-" + id() + "\",\"amountMinor\":1000,\"reason\":\"顾客取消\"}", fresh(APP1, SECRET1), null));
        assertThat(refund.get("payRefundNo").asString()).isNotBlank();

        MockHttpServletResponse overLimit = call("POST", "/open/pay/v1/orders/" + no + "/refunds", Map.of(),
                "{\"outRefundNo\":\"RF-" + id() + "\",\"amountMinor\":4500,\"reason\":\"超额\"}", fresh(APP1, SECRET1), null);
        assertThat(overLimit.getStatus()).as("日限额 5000，已退 1000，再退 4500 超").isIn(400, 409, 422);

        MockHttpServletResponse noScope = call("POST", "/open/pay/v1/orders/" + no + "/refunds", Map.of(),
                "{\"outRefundNo\":\"RF-" + id() + "\",\"amountMinor\":100,\"reason\":\"x\"}", fresh(APP2, SECRET2), null);
        assertThat(noScope.getStatus()).isIn(403, 404);
    }

    @Test
    @DisplayName("★★★ 第三方的单付成功后，回调 / 对账把支付单号交给交易补单：不是交易单，静默跳过、不抛（否则通道无限重推）")
    void partnerPaymentNeverReachesTrade() throws Exception {
        String no = create(APP1, SECRET1, "OUT-" + id(), 700).get("payOrderNo").asString();
        JsonNode started = ok(call("POST", "/open/pay/v1/orders/" + no + "/pay", Map.of(), "{\"payMethod\":\"TEST_PAY\"}",
                fresh(APP1, SECRET1), null));
        String orderNo = settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST",
                "T-" + id(), 700, System.currentTimeMillis()));
        assertThat(orderNo).as("收款账本认领到的「订单号」就是支付单号").isEqualTo(no);
        // 回调控制器与三个对账器拿到这个号都会去补交易单 —— 它不是交易单
        orderRepair.markPaid(orderNo, "TEST", "T-X");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE order_no=?", Integer.class, no)).isZero();
    }

    @Test
    @DisplayName("★★ 门店可用支付方式：来自门店绑定 × 支付产品")
    void payMethodsOfStore() throws Exception {
        JsonNode methods = ok(call("GET", "/open/pay/v1/stores/" + storeNo + "/pay-methods", Map.of("market", "CN"), "",
                fresh(APP1, SECRET1), null));
        assertThat(methods.toString()).contains("TEST_PAY");
    }

    @Test
    @DisplayName("★★ 关单：未付的单关掉，再查是 CLOSED")
    void closeUnpaid() throws Exception {
        String no = create(APP1, SECRET1, "OUT-" + id(), 800).get("payOrderNo").asString();
        ok(call("POST", "/open/pay/v1/orders/" + no + "/close", Map.of(), "{}", fresh(APP1, SECRET1), null));
        assertThat(ok(call("GET", "/open/pay/v1/orders/" + no, Map.of(), "", fresh(APP1, SECRET1), null))
                .get("status").asString()).isEqualTo("CLOSED");
    }
}
