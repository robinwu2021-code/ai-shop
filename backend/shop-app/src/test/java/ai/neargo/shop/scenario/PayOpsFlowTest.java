package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import ai.neargo.shop.support.TestLogin;
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
 * 支付运营端（TDD-支付服务-I5接入层 S9，F50 F51 F53 F57）：支付单全貌、退款复核、业务通知重推、支付异常处置、收款码风控停用。
 * 上下文与 {@code RefundReviewFlowTest} 同一份（同库、开复核）。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.pay.refund.review=true"})
@ActiveProfiles("test")
class PayOpsFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private TestPayGateway bank;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String admin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private MockHttpServletResponse call(String token, MockHttpServletRequestBuilder b) throws Exception {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return mvc().perform(b.contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
    }

    private JsonNode data(MockHttpServletResponse r) throws Exception {
        JsonNode n = json.readTree(r.getContentAsString());
        assertThat(n.get("code").asInt()).as(r.getContentAsString()).isZero();
        return n.get("data");
    }

    private int code(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString()).get("code").asInt();
    }

    /** 商城内部单：建支付单 → 开尝试 → 测试网关付成 → 入账（与 RefundReviewFlowTest 同一条路） */
    private String paidOrder() {
        String no = "PO-OPS-" + UUID.randomUUID().toString().substring(0, 8);
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        var handle = payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store, "门店",
                "主体", "CN", 1990, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", 1990, "goods"));
        String trade = bank.placeOrder(no, 1990);
        bank.markPaid(no);
        settle.settlePayment(new PaymentSettled(no, "TEST", trade, 1990, System.currentTimeMillis(), "CNY"));
        return handle.payOrderNo();
    }

    @Test
    @DisplayName("★★★ 没有运营令牌一律 401（M5a：支付运营走业务服务的运营链，与其它运营端同一套账号体系）")
    void unauthenticatedIsRejected() throws Exception {
        assertThat(call(null, get("/ops/payments/orders")).getStatus()).isEqualTo(401);
        assertThat(call(null, post("/ops/payments/refunds/PYR-X/review").content("{}")).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("★★★ 支付单全貌：一张已付的单在列表里查得到；详情里有交易尝试、流水凭证")
    void orderTimeline() throws Exception {
        String po = paidOrder();
        String token = admin();
        JsonNode list = data(call(token, get("/ops/payments/orders").param("payOrderNo", po)));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        JsonNode d = data(call(token, get("/ops/payments/orders/" + po)));
        assertThat(d.get("attempts").size()).isPositive();
        assertThat(d.get("vouchers").toString()).contains("PAY_SUCCEEDED");
        assertThat(code(call(token, get("/ops/payments/orders/PYO-NOPE")))).isNotZero();
    }

    @Test
    @DisplayName("★★★ 退款复核：待复核的退款在队列里；运营通过一次进第二复核；同一人不能再复核")
    void refundReview() throws Exception {
        String po = paidOrder();
        String out = "RF-OPS-" + UUID.randomUUID().toString().substring(0, 8);
        var pending = payments.requestRefund(new InternalRefundCmd(PaymentPort.MALL_ORDER, po, out, "AS-" + out, 200,
                "七天无理由", "OP-1"));
        assertThat(pending.status()).isEqualTo("PENDING_REVIEW");
        String token = admin();
        assertThat(data(call(token, get("/ops/payments/refunds").param("status", "PENDING_REVIEW"))).toString())
                .contains(pending.payRefundNo());

        JsonNode first = data(call(token, post("/ops/payments/refunds/" + pending.payRefundNo() + "/review")
                .content("{\"approve\":true}")));
        assertThat(first.get("status").asString()).isEqualTo("PENDING_SECOND_REVIEW");
        assertThat(code(call(token, post("/ops/payments/refunds/" + pending.payRefundNo() + "/review")
                .content("{\"approve\":true}")))).as("两人复核：同一个人不能通过两次").isNotZero();
    }

    @Test
    @DisplayName("★★ 业务通知手动重推：投递记录记 MANUAL 与运营账号")
    void notifyResend() throws Exception {
        String po = paidOrder();
        Long orderId = jdbc.queryForObject("SELECT id FROM pay_order WHERE pay_order_no=?", Long.class, po);
        jdbc.update("INSERT INTO pay_biz_notify (trace_id, event_type, event_key, state_version, target_type, pay_app_id,"
                + " target_url, pay_order_id, payload_masked, status, attempts, created_at, updated_at) VALUES"
                + " ('T', 'PAYMENT_SUCCEEDED', ?, 2, 'WEBHOOK', 1, 'http://127.0.0.1:1/hook', ?, '{}', 'GIVEN_UP', 3, NOW(), NOW())",
                "PAYMENT_SUCCEEDED|O" + orderId + "|ops", orderId);
        Long id = jdbc.queryForObject("SELECT id FROM pay_biz_notify WHERE event_key=?", Long.class,
                "PAYMENT_SUCCEEDED|O" + orderId + "|ops");
        String token = admin();
        assertThat(data(call(token, get("/ops/payments/notifies").param("status", "GIVEN_UP"))).toString())
                .contains(String.valueOf(id));
        data(call(token, post("/ops/payments/notifies/" + id + "/resend")));
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM pay_biz_notify_attempt WHERE pay_biz_notify_id=?"
                + " ORDER BY attempt DESC LIMIT 1", String.class, id)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT triggered_by FROM pay_biz_notify_attempt WHERE pay_biz_notify_id=?"
                + " ORDER BY attempt DESC LIMIT 1", String.class, id)).isNotBlank();
    }

    @Test
    @DisplayName("★★ 支付异常判定无需处置：状态 IGNORED、记处置人；收款码运营可风控停用")
    void exceptionIgnoreAndQrDisable() throws Exception {
        String po = paidOrder();
        Long orderId = jdbc.queryForObject("SELECT id FROM pay_order WHERE pay_order_no=?", Long.class, po);
        Long attempt = jdbc.queryForObject("SELECT id FROM stl_payment WHERE pay_order_id=? AND direction='PAY'"
                + " ORDER BY id LIMIT 1", Long.class, orderId);
        jdbc.update("INSERT INTO pay_exception (kind, pay_order_id, stl_payment_id, amount_minor, currency, status,"
                + " created_at, updated_at) VALUES ('AMOUNT_MISMATCH', ?, ?, 1990, 'CNY', 'OPEN', NOW(), NOW())", orderId, attempt);
        Long excId = jdbc.queryForObject("SELECT id FROM pay_exception WHERE pay_order_id=? AND kind='AMOUNT_MISMATCH'",
                Long.class, orderId);
        String token = admin();
        assertThat(data(call(token, get("/ops/payments/exceptions").param("status", "OPEN"))).toString())
                .contains(po);
        data(call(token, post("/ops/payments/exceptions/" + excId + "/ignore").content("{\"note\":\"线下已退\"}")));
        assertThat(jdbc.queryForObject("SELECT status FROM pay_exception WHERE id=?", String.class, excId))
                .isEqualTo("IGNORED");

        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        String qr = "QR-OPS-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO pay_qr_code (qr_code, store_id, kind, currency, single_limit_minor, daily_limit_minor,"
                + " status, created_by_type, created_at, updated_at) VALUES (?, ?, 'STORE', 'CNY', 100, 1000, 'ACTIVE',"
                + " 'BIZ', NOW(), NOW())", qr, store);
        assertThat(data(call(token, get("/ops/payments/qr-codes").param("storeId", String.valueOf(store))
                .param("status", "ACTIVE"))).toString()).as("M5b.4 运营看得到全部门店的码").contains(qr);
        data(call(token, put("/ops/payments/qr-codes/" + qr + "/disabled")));
        assertThat(jdbc.queryForObject("SELECT status FROM pay_qr_code WHERE qr_code=?", String.class, qr))
                .isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("★★★ M5b.2 请求记录：按入口、按追踪号查得到；详情给的是写入时脱敏过的报文；内部来源列原样带出")
    void requestLogsByTraceId() throws Exception {
        String trace = "T-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO pay_api_request_log (trace_id, entry, api, pay_action, source_type, client_type, http_status, outcome,"
                + " request_masked, received_at, source_service, source_module, created_at, updated_at) VALUES (?, 'INTERNAL_HTTP',"
                + " 'GET /internal/pay/v1/ops/orders', 'QUERY', 'INTERNAL', 'SERVER', 200, 'ACCEPTED', '{\"phone\":\"138****0000\"}', NOW(),"
                + " 'shop', 'payclient', NOW(), NOW())", trace);
        String token = admin();

        JsonNode rows = data(call(token, get("/ops/payments/request-logs").param("traceId", trace)));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("entry").asString()).isEqualTo("INTERNAL_HTTP");
            assertThat(r.get("sourceService").asString()).isEqualTo("shop");
        });
        assertThat(data(call(token, get("/ops/payments/request-logs").param("traceId", trace).param("entry", "OPEN_API"))))
                .isEmpty();
        JsonNode detail = data(call(token, get("/ops/payments/request-logs/" + rows.get(0).get("id").asLong())));
        assertThat(detail.get("requestMasked").asString()).contains("138****0000");
    }
}
