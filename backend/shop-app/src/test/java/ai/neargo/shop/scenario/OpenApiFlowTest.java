package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.backfill.PayIntegrity;
import ai.neargo.shop.pay.acquiring.service.PayExceptionService;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    @Autowired private BizNotifyService notifier;
    @Autowired private PayExceptionService payExceptions;
    @Autowired private PayIntegrity integrity;

    /** 接入方的 webhook 接收端：真起一个 HTTP 服务，不打桩发送器 —— 签名、头、超时都走真的 */
    private record Hook(Map<String, String> headers, String path, String body) {
    }

    private static final List<Hook> HOOKS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean HOOK_FAILS = new AtomicBoolean(false);
    private static HttpServer hookServer;

    private static synchronized String hookUrl() throws Exception {
        if (hookServer == null) {
            hookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            hookServer.createContext("/hook", ex -> {
                Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                ex.getRequestHeaders().forEach((k, v) -> h.put(k, v.get(0)));
                HOOKS.add(new Hook(h, ex.getRequestURI().getPath(),
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] out = (HOOK_FAILS.get() ? "busy" : "SUCCESS").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(HOOK_FAILS.get() ? 500 : 200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            hookServer.start();
        }
        return "http://127.0.0.1:" + hookServer.getAddress().getPort() + "/hook";
    }

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

        // 上面那笔也超了这一单的可退余额（3000）—— 单靠它分不出是哪条规则挡的。另起一单，余额够、只有日限额不够
        String big = create(APP1, SECRET1, "OUT-" + id(), 4500).get("payOrderNo").asString();
        JsonNode bigStarted = ok(call("POST", "/open/pay/v1/orders/" + big + "/pay", Map.of(),
                "{\"payMethod\":\"TEST_PAY\"}", fresh(APP1, SECRET1), null));
        settle.settlePayment(new SettlePort.PaymentSettled(bigStarted.get("outTradeNo").asString(), "TEST",
                "T-" + id(), 4500, System.currentTimeMillis()));
        MockHttpServletResponse dailyOnly = call("POST", "/open/pay/v1/orders/" + big + "/refunds", Map.of(),
                "{\"outRefundNo\":\"RF-" + id() + "\",\"amountMinor\":4500,\"reason\":\"整单\"}", fresh(APP1, SECRET1), null);
        assertThat(json.readTree(dailyOnly.getContentAsString()).get("code").asInt())
                .as("余额够（4500），日限额不够（已退 1000 + 4500 > 5000）").isEqualTo(ErrorCode.PAY_APP_REFUND_LIMIT.code());

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

    /** 建单 → 发起 → 渠道到账：返回支付单号 */
    private String paidOrder(long amount) throws Exception {
        String no = create(APP1, SECRET1, "OUT-" + id(), amount).get("payOrderNo").asString();
        JsonNode started = ok(call("POST", "/open/pay/v1/orders/" + no + "/pay", Map.of(), "{\"payMethod\":\"TEST_PAY\"}",
                fresh(APP1, SECRET1), null));
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", "T-" + id(),
                amount, System.currentTimeMillis()));
        return no;
    }

    private Map<String, Object> notifyOf(String payOrderNo, String eventType) {
        return jdbc.queryForMap("SELECT n.* FROM pay_biz_notify n JOIN pay_order o ON o.id=n.pay_order_id"
                + " WHERE o.pay_order_no=? AND n.event_type=?", payOrderNo, eventType);
    }

    @Test
    @DisplayName("★★★ webhook：支付成功同事务落一条答复记录；投递是带签名的 POST（与请求同构），2xx 即送达、每次投递一行")
    void webhookOnSuccessIsSignedAndDelivered() throws Exception {
        jdbc.update("UPDATE pay_app SET webhook_url=? WHERE app_no=?", hookUrl(), APP1);
        HOOK_FAILS.set(false);
        String no = paidOrder(1200);
        Map<String, Object> n = notifyOf(no, "PAYMENT_SUCCEEDED");
        assertThat(n.get("status")).isEqualTo("PENDING");
        assertThat(n.get("target_type")).isEqualTo("WEBHOOK");
        assertThat(n.get("target_url")).isEqualTo(hookUrl());

        notifier.deliverDue(Instant.now(), 100);

        Hook h = HOOKS.stream().filter(x -> x.body().contains(no)).findFirst().orElseThrow();
        assertThat(h.headers().get("X-App-Id")).isEqualTo(APP1);
        String expected = sign(SECRET1, "POST", "/hook", Map.of(), APP1, Long.parseLong(h.headers().get("X-Timestamp")),
                h.headers().get("X-Nonce"), h.body());
        assertThat(h.headers().get("X-Signature")).as("接入方用自己的密钥按同一规则能验过").isEqualTo(expected);
        JsonNode body = json.readTree(h.body());
        assertThat(body.get("eventType").asString()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(body.get("eventId").asString()).isEqualTo(n.get("event_key"));
        assertThat(body.get("data").get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(body.get("data").get("paidMinor").asLong()).isEqualTo(1200);

        Map<String, Object> after = notifyOf(no, "PAYMENT_SUCCEEDED");
        assertThat(after.get("status")).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT outcome FROM pay_biz_notify_attempt WHERE pay_biz_notify_id=?",
                String.class, after.get("id"))).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("★★★ webhook：对方 5xx 退避重试，超过 24 小时放弃；运营手动重推后送达（记 MANUAL）")
    void webhookRetriesGivesUpAndCanBeRedelivered() throws Exception {
        jdbc.update("UPDATE pay_app SET webhook_url=? WHERE app_no=?", hookUrl(), APP1);
        String no = paidOrder(900);
        Long id = ((Number) notifyOf(no, "PAYMENT_SUCCEEDED").get("id")).longValue();
        HOOK_FAILS.set(true);
        try {
            Instant now = Instant.now();
            notifier.deliverDue(now, 100);
            Map<String, Object> failed = notifyOf(no, "PAYMENT_SUCCEEDED");
            assertThat(failed.get("status")).isEqualTo("FAILED");
            assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
            notifier.deliverDue(now, 100);
            assertThat(((Number) notifyOf(no, "PAYMENT_SUCCEEDED").get("attempts")).intValue())
                    .as("没到下次时间不重投").isEqualTo(1);

            notifier.deliverDue(now.plus(Duration.ofHours(25)), 100);
            assertThat(notifyOf(no, "PAYMENT_SUCCEEDED").get("status")).isEqualTo("GIVEN_UP");
        } finally {
            HOOK_FAILS.set(false);
        }
        notifier.redeliver(id, "OP-WH");
        notifier.deliverDue(Instant.now(), 100);
        assertThat(notifyOf(no, "PAYMENT_SUCCEEDED").get("status")).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM pay_biz_notify_attempt WHERE pay_biz_notify_id=?"
                + " ORDER BY attempt DESC LIMIT 1", String.class, id)).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("★★ webhook：关单也答复；没有 webhook 地址的接入方不落 WEBHOOK 行")
    void closeIsNotifiedAndNoUrlNoRow() throws Exception {
        jdbc.update("UPDATE pay_app SET webhook_url=? WHERE app_no=?", hookUrl(), APP1);
        String no = create(APP1, SECRET1, "OUT-" + id(), 300).get("payOrderNo").asString();
        ok(call("POST", "/open/pay/v1/orders/" + no + "/close", Map.of(), "{}", fresh(APP1, SECRET1), null));
        assertThat(notifyOf(no, "PAYMENT_CLOSED").get("target_type")).isEqualTo("WEBHOOK");

        jdbc.update("UPDATE pay_app SET webhook_url=NULL WHERE app_no=?", APP2);
        String other = create(APP2, SECRET2, "OUT-" + id(), 300).get("payOrderNo").asString();
        ok(call("POST", "/open/pay/v1/orders/" + other + "/close", Map.of(), "{}", fresh(APP2, SECRET2), null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_biz_notify n JOIN pay_order o ON o.id=n.pay_order_id"
                + " WHERE o.pay_order_no=?", Integer.class, other)).isZero();
    }

    // ------------------------------------------------------------------ 收银台（S7，顾客不签名）

    private MockHttpServletResponse visit(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder b = "GET".equals(method) ? get(path) : post(path);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private static String tokenOf(String checkoutUrl) {
        return checkoutUrl.substring(checkoutUrl.indexOf("?t=") + 3);
    }

    @Test
    @DisplayName("★★★ 托管收银台：建单返回带令牌的 checkoutUrl；顾客凭令牌看单（字段白名单）、选方式、看结果；结果不入账")
    void hostedCheckout() throws Exception {
        JsonNode created = create(APP1, SECRET1, "OUT-" + id(), 1500);
        String no = created.get("payOrderNo").asString();
        String token = tokenOf(created.get("checkoutUrl").asString());

        MockHttpServletResponse viewed = visit("GET", "/checkout/v1/sessions/" + token, null);
        assertThat(viewed.getStatus()).isEqualTo(200);
        assertThat(viewed.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(viewed.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        JsonNode v = ok(viewed);
        assertThat(v.get("amountMinor").asLong()).isEqualTo(1500);
        assertThat(v.get("currencyScale").asInt()).as("小数位由市场表给，前端按它排版").isEqualTo(2);
        assertThat(v.get("market").asString()).isEqualTo("CN");
        assertThat(v.get("payeeName").asString()).startsWith("开放 E-OA-");
        assertThat(v.get("payMethods").toString()).contains("TEST_PAY");
        assertThat(v.has("outOrderNo") || v.has("storeId") || v.has("payMerchantId")).as("字段白名单").isFalse();

        JsonNode started = ok(visit("POST", "/checkout/v1/sessions/" + token + "/pay", "{\"payMethod\":\"TEST_PAY\"}"));
        assertThat(started.get("payParams").get("prepayId").asString()).startsWith("test_");
        assertThat(ok(visit("GET", "/checkout/v1/sessions/" + token + "/result", null)).get("result").asString())
                .isEqualTo("PROCESSING");
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", "T-" + id(),
                1500, System.currentTimeMillis()));
        assertThat(ok(visit("GET", "/checkout/v1/sessions/" + token + "/result", null)).get("result").asString())
                .isEqualTo("SUCCEEDED");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_checkout_session WHERE token_sha256=?", Integer.class,
                token)).as("库里没有令牌明文").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE entry='CHECKOUT' AND api LIKE ?",
                Integer.class, "%" + token + "%")).as("请求记录里没有令牌明文").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE entry='CHECKOUT'", Integer.class))
                .isPositive();
        assertThat(no).isNotBlank();
    }

    @Test
    @DisplayName("★★★ 令牌：不存在 404；过期 410；关单后立即作废 410")
    void tokenLifecycle() throws Exception {
        assertThat(visit("GET", "/checkout/v1/sessions/no-such-token", null).getStatus()).isEqualTo(404);

        JsonNode a = create(APP1, SECRET1, "OUT-" + id(), 500);
        String ta = tokenOf(a.get("checkoutUrl").asString());
        ok(call("POST", "/open/pay/v1/orders/" + a.get("payOrderNo").asString() + "/close", Map.of(), "{}",
                fresh(APP1, SECRET1), null));
        assertThat(visit("GET", "/checkout/v1/sessions/" + ta, null).getStatus()).isEqualTo(410);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_checkout_session WHERE pay_order_id=(SELECT id FROM pay_order"
                + " WHERE pay_order_no=?)", String.class, a.get("payOrderNo").asString()))
                .as("关单当场作废令牌，不靠下次访问时再判").isEqualTo("REVOKED");
        assertThat(visit("POST", "/checkout/v1/sessions/" + ta + "/pay", "{\"payMethod\":\"TEST_PAY\"}").getStatus())
                .isEqualTo(410);

        JsonNode b = create(APP1, SECRET1, "OUT-" + id(), 500);
        String tb = tokenOf(b.get("checkoutUrl").asString());
        jdbc.update("UPDATE pay_checkout_session SET expire_at=? WHERE pay_order_id=(SELECT id FROM pay_order"
                + " WHERE pay_order_no=?)", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),
                b.get("payOrderNo").asString());
        assertThat(visit("GET", "/checkout/v1/sessions/" + tb, null).getStatus()).isEqualTo(410);
    }

    @Test
    @DisplayName("★★★ 静态码：看门店 → 顾客输金额建单；超单笔上限、超单码日累计拒绝；停用后 404")
    void staticCode() throws Exception {
        String code = "QR" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO pay_qr_code (qr_code, store_id, kind, currency, single_limit_minor, daily_limit_minor,"
                + " status, created_by_type, created_at, updated_at) VALUES (?, ?, 'STORE', 'CNY', 5000, 8000, 'ACTIVE',"
                + " 'BIZ', NOW(), NOW())", code, storeId);
        JsonNode c = ok(visit("GET", "/checkout/v1/codes/" + code, null));
        assertThat(c.get("storeName").asString()).startsWith("开放门店 ");
        assertThat(c.get("singleLimitMinor").asLong()).isEqualTo(5000);

        assertThat(visit("POST", "/checkout/v1/codes/" + code + "/orders", "{\"amountMinor\":6000}").getStatus())
                .as("超单笔").isEqualTo(422);
        JsonNode first = ok(visit("POST", "/checkout/v1/codes/" + code + "/orders", "{\"amountMinor\":4000}"));
        String token = first.get("token").asString();
        assertThat(ok(visit("GET", "/checkout/v1/sessions/" + token, null)).get("amountMinor").asLong()).isEqualTo(4000);
        ok(visit("POST", "/checkout/v1/codes/" + code + "/orders", "{\"amountMinor\":4000}"));
        assertThat(visit("POST", "/checkout/v1/codes/" + code + "/orders", "{\"amountMinor\":1}").getStatus())
                .as("单码日累计 8000").isEqualTo(422);
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM pay_order WHERE pay_order_no=?",
                first.get("payOrderNo").asString());
        assertThat(order.get("source_type")).isEqualTo("CHECKOUT");
        assertThat(order.get("biz_no")).isEqualTo(code);

        jdbc.update("UPDATE pay_qr_code SET status='DISABLED' WHERE qr_code=?", code);
        assertThat(visit("GET", "/checkout/v1/codes/" + code, null).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("★★★ 关单后到账（W3 E04）：记 LATE_PAYMENT；原路退回走那笔尝试本身，不碰已关支付单的可退余额；退成即结案、再退不重复")
    void latePaymentIsRefundedToTheCustomer() throws Exception {
        String no = create(APP1, SECRET1, "OUT-" + id(), 650).get("payOrderNo").asString();
        JsonNode started = ok(call("POST", "/open/pay/v1/orders/" + no + "/pay", Map.of(), "{\"payMethod\":\"TEST_PAY\"}",
                fresh(APP1, SECRET1), null));
        ok(call("POST", "/open/pay/v1/orders/" + no + "/close", Map.of(), "{}", fresh(APP1, SECRET1), null));
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", "T-" + id(),
                650, System.currentTimeMillis()));
        Map<String, Object> exc = jdbc.queryForMap("SELECT e.* FROM pay_exception e JOIN pay_order o ON o.id=e.pay_order_id"
                + " WHERE o.pay_order_no=?", no);
        assertThat(exc.get("kind")).isEqualTo("LATE_PAYMENT");
        assertThat(exc.get("status")).isEqualTo("OPEN");
        Long excId = ((Number) exc.get("id")).longValue();

        payExceptions.refund(excId, "OP-LATE");

        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM pay_exception WHERE id=?", excId);
        assertThat(after.get("status")).isIn("AUTO_REFUNDING", "RESOLVED");
        assertThat(after.get("handled_by")).isEqualTo("OP-LATE");
        Map<String, Object> refundRow = jdbc.queryForMap("SELECT * FROM stl_payment WHERE direction='REFUND'"
                + " AND after_sale_no=?", "PAYEXC:" + excId);
        assertThat(((Number) refundRow.get("amount_minor")).longValue()).isEqualTo(650);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund r JOIN pay_order o ON o.id=r.pay_order_id"
                + " WHERE o.pay_order_no=?", Integer.class, no)).as("已关支付单不开退款单").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE pay_order_no=?", String.class, no))
                .isEqualTo("CLOSED");
        if ("SUCCESS".equals(refundRow.get("status"))) {
            assertThat(after.get("status")).as("渠道已退成").isEqualTo("RESOLVED");
        }

        payExceptions.refund(excId, "OP-LATE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE direction='REFUND' AND after_sale_no=?",
                Integer.class, "PAYEXC:" + excId)).as("再点一次不重复退").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 资金恒等式①（W3 E09）：每张支付单「已付 − 已退 = 流水净额」；改坏一张，巡检点得出它")
    void ledgerMatchesEveryPaidOrder() throws Exception {
        String healthy = paidOrder(880);
        String broken = paidOrder(990);
        assertThat(integrity.ledgerMismatchedOrders(10_000)).doesNotContain(healthy, broken);
        jdbc.update("UPDATE pay_order SET paid_minor = paid_minor + 1 WHERE pay_order_no=?", broken);
        assertThat(integrity.ledgerMismatchedOrders(10_000)).contains(broken).doesNotContain(healthy);
        assertThat(integrity.unbalancedVouchers()).isZero();
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
