package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 业务单收款汇总（TDD-业务单收款汇总 AC1–AC8）：同一业务单号下，付款链接、外部记账、储值扣款加起来算「已收 / 还差」。
 *
 * <p>夹具与 {@link BizPaymentLinkFlowTest} 同一套开店方式（CN / CNY，挂 TEST 收款商户）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-receivable;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BizReceivableFlowTest {

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

    private record Shop(String token, String storeNo, Long storeId) {
    }

    /** 与 BizPayFlowTest 同一套开店方式：入驻审核通过 → 店主令牌 → 默认店挂 TEST 收款商户 */
    private Shop shop() throws Exception {
        String phone = "137" + String.format("%08d", (long) (Math.random() * 1e8));
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"收款汇总" + id() + "\",\"subject\":\"INDIVIDUAL_BIZ\","
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
        String pm = "PM-RV-" + id();
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, sec_key_version, status,"
                + " invoice_capable, priority, created_at, updated_at) VALUES (?, 'PLATFORM', NULL, 'TEST', 'CN', 'CNY',"
                + " 'MERCHANT_OWN', 'AGGREGATED', ?, '', 0, 'ACTIVE', 0, 1000, NOW(), NOW())", "P" + pm, "PLATFORM-" + pm);
        Long parent = jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, "P" + pm);
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, parent_pay_merchant_id, sec_key_version,"
                + " status, invoice_capable, priority, settle_cycle, settle_account_masked, created_at, updated_at)"
                + " VALUES (?, 'STORE', ?, 'TEST', 'CN', 'CNY', 'PLATFORM_SUB', 'AGGREGATED', ?, ?, ?, 0, 'ACTIVE', 0, 100,"
                + " 'T1', '****8901', NOW(), NOW())", pm, storeId, "PLATFORM-" + pm, "SUB-" + pm, parent);
        Long merchantId = jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, pm);
        jdbc.update("INSERT INTO pay_merchant_product (pay_merchant_id, pay_channel_product_id, status, source,"
                + " quota_limit_minor, quota_used_minor, created_at, updated_at) SELECT ?, id, 'OPEN', 'OPS', 0, 0,"
                + " NOW(), NOW() FROM pay_channel_product WHERE product_code='TEST_ONLINE'", merchantId);
        bindings.bind(storeId, "TEST", merchantId, "OP-RV");
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

    private MockHttpServletResponse as(Shop s, MockHttpServletRequestBuilder b) throws Exception {
        return call(s.token(), s.storeNo(), b);
    }

    private JsonNode data(MockHttpServletResponse r) throws Exception {
        JsonNode n = json.readTree(r.getContentAsString());
        assertThat(n.get("code").asInt()).as(r.getContentAsString()).isZero();
        return n.get("data");
    }

    private int code(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString()).get("code").asInt();
    }

    /** 顾客经收银台付掉；返回渠道交易号 */
    private String pay(String token, long amount) throws Exception {
        MockHttpServletResponse r = mvc().perform(post("/checkout/v1/sessions/" + token + "/pay")
                .contentType(MediaType.APPLICATION_JSON).content("{\"payMethod\":\"TEST_PAY\"}")).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode started = json.readTree(r.getContentAsString()).get("data");
        String tradeNo = "T" + id() + id();
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", tradeNo,
                amount, System.currentTimeMillis()));
        return tradeNo;
    }

    private JsonNode link(Shop s, long amount, String bizNo) throws Exception {
        return data(as(s, post("/biz/payment/collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":" + amount + ",\"subject\":\"结账\",\"expireMinutes\":30"
                        + (bizNo == null ? "" : ",\"bizNo\":\"" + bizNo + "\"") + "}")));
    }

    private JsonNode offline(Shop s, long amount, String bizNo) throws Exception {
        return data(as(s, post("/biz/payment/offline-records").header("Idempotency-Key", "k-" + id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":" + amount + ",\"currency\":\"CNY\",\"method\":\"CASH\",\"bizNo\":\""
                        + bizNo + "\"}")));
    }

    private void storedValue(Shop s, String bizNo, long amount, String currency, String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String no = "EU" + id() + id();
        jdbc.update("INSERT INTO ent_usage (tenant_no, usage_no, operation_no, user_no, entity_no, store_no, store_name,"
                + " asset_kind, title, amount_minor, currency, items_snapshot, source_order_no, operator_no, status,"
                + " occurred_at, created_at, updated_at, created_by, updated_by) VALUES ('MAIN', ?, ?, 'U1', 'E1', ?,"
                + " '店', 'STORED_VALUE', '储值', ?, ?, '[]', ?, 'OP', ?, ?, ?, ?, 'T', 'T')",
                no, "OP" + no, s.storeNo(), amount, currency, bizNo, status, now, now, now);
    }

    private MockHttpServletResponse summary(Shop s, String bizNo, String query) throws Exception {
        return as(s, get("/biz/stores/" + s.storeNo() + "/receivables/" + bizNo + query));
    }

    private static JsonNode line(JsonNode summary, String refNo) {
        for (JsonNode l : summary.get("lines")) {
            if (refNo.equals(l.get("refNo").asString())) {
                return l;
            }
        }
        throw new AssertionError(refNo + " not in " + summary);
    }

    @Test
    @DisplayName("★★★ AC1/AC2/AC5/AC8 扫码一部分 + 现金一部分收齐；没付的链接只列不算；退款后重算")
    void splitPaymentAddsUpAndRefundReopensTheGap() throws Exception {
        Shop s = shop();
        String bizNo = "FST" + id();
        JsonNode paid = link(s, 30000, bizNo);
        pay(paid.get("token").asString(), 30000);
        JsonNode pending = link(s, 20000, bizNo);
        JsonNode other = link(s, 10000, null);
        pay(other.get("token").asString(), 10000);
        assertThat(jdbc.queryForObject("SELECT biz_no FROM pay_order WHERE pay_order_no=?", String.class,
                other.get("payOrderNo").asString())).startsWith("BZC");
        String cash = offline(s, 20000, bizNo).get("recordNo").asString();

        JsonNode r = data(summary(s, bizNo, "?dueMinor=50000&currency=CNY"));
        assertThat(r.get("bizNo").asString()).isEqualTo(bizNo);
        assertThat(r.get("receivedMinor").asLong()).isEqualTo(50000);
        assertThat(r.get("outstandingMinor").asLong()).isZero();
        assertThat(r.get("overpaidMinor").asLong()).isZero();
        assertThat(r.get("lines")).hasSize(3);
        assertThat(line(r, paid.get("payOrderNo").asString()).get("countedMinor").asLong()).isEqualTo(30000);
        JsonNode open = line(r, pending.get("payOrderNo").asString());
        assertThat(open.get("kind").asString()).isEqualTo("PAY_LINK");
        assertThat(open.get("status").asString()).isEqualTo("PENDING");
        assertThat(open.get("countedMinor").asLong()).isZero();
        assertThat(line(r, cash).get("kind").asString()).isEqualTo("OFFLINE");

        jdbc.update("UPDATE pay_order SET refunded_minor=10000, status='PARTIALLY_REFUNDED' WHERE pay_order_no=?",
                paid.get("payOrderNo").asString());
        r = data(summary(s, bizNo, "?dueMinor=50000&currency=CNY"));
        assertThat(r.get("receivedMinor").asLong()).isEqualTo(40000);
        assertThat(r.get("outstandingMinor").asLong()).isEqualTo(10000);
    }

    @Test
    @DisplayName("★★ AC3 外部记账作废不计、更正按新金额计")
    void offlineVoidAndCorrectionAreHonoured() throws Exception {
        Shop s = shop();
        String bizNo = "BWO" + id();
        JsonNode a = offline(s, 10000, bizNo);
        JsonNode b = offline(s, 20000, bizNo);
        data(as(s, post("/biz/payment/offline-records/" + a.get("recordNo").asString() + "/void")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"记错\",\"expectedVersion\":1}")));
        data(as(s, post("/biz/payment/offline-records/" + b.get("recordNo").asString() + "/correct")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":25000,\"currency\":\"CNY\","
                        + "\"method\":\"CASH\",\"reason\":\"少记\",\"expectedVersion\":1}")));
        JsonNode r = data(summary(s, bizNo, "?dueMinor=20000&currency=CNY"));
        assertThat(r.get("receivedMinor").asLong()).isEqualTo(25000);
        assertThat(r.get("outstandingMinor").asLong()).isZero();
        assertThat(r.get("overpaidMinor").asLong()).isEqualTo(5000);
        assertThat(line(r, a.get("recordNo").asString()).get("status").asString()).isEqualTo("VOIDED");
        assertThat(line(r, a.get("recordNo").asString()).get("countedMinor").asLong()).isZero();
    }

    @Test
    @DisplayName("★★ AC4/AC6 储值扣款按来源单号计入，冲回不计；币种不符单列为异常")
    void storedValueCountsCommittedOnlyAndForeignCurrencyIsAnAnomaly() throws Exception {
        Shop s = shop();
        String bizNo = "BWO" + id();
        storedValue(s, bizNo, 15000, "CNY", "COMMITTED");
        storedValue(s, bizNo, 5000, "CNY", "REVERSED");
        storedValue(s, bizNo, 7000, "USD", "COMMITTED");
        JsonNode r = data(summary(s, bizNo, "?dueMinor=15000&currency=CNY"));
        assertThat(r.get("receivedMinor").asLong()).isEqualTo(15000);
        assertThat(r.get("outstandingMinor").asLong()).isZero();
        assertThat(r.get("lines")).hasSize(3);
        assertThat(r.get("anomalies")).hasSize(1);
        assertThat(r.get("anomalies").get(0).get("currency").asString()).isEqualTo("USD");
    }

    @Test
    @DisplayName("★★ AC7 只读本店；参数不合法 10400；带了非法单号的付款链接被拒")
    void scopedAndValidated() throws Exception {
        Shop s = shop();
        Shop other = shop();
        String bizNo = "FST" + id();
        offline(other, 10000, bizNo);
        JsonNode r = data(summary(s, bizNo, "?dueMinor=10000&currency=CNY"));
        assertThat(r.get("receivedMinor").asLong()).isZero();
        assertThat(r.get("lines")).isEmpty();
        assertThat(data(summary(other, bizNo, "?dueMinor=10000&currency=CNY")).get("receivedMinor").asLong())
                .isEqualTo(10000);
        assertThat(code(as(s, get("/biz/stores/" + other.storeNo() + "/receivables/" + bizNo
                + "?dueMinor=1&currency=CNY")))).isNotZero();
        assertThat(code(summary(s, bizNo, "?dueMinor=-1&currency=CNY"))).isEqualTo(10400);
        assertThat(code(summary(s, bizNo, "?dueMinor=1&currency=cny"))).isEqualTo(10400);
        assertThat(code(summary(s, bizNo, "?currency=CNY"))).isEqualTo(10400);
        assertThat(code(summary(s, "a".repeat(65), "?dueMinor=1&currency=CNY"))).isEqualTo(10400);
        assertThat(code(as(s, post("/biz/payment/collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100,\"bizNo\":\"bad no\"}")))).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★★ W1.4 订金：记在预约号下的外部记账，结账时按 relatedBizNo 计入工单已收，明细标出来自哪张单")
    void depositRecordedOnTheAppointmentCountsTowardTheWorkOrder() throws Exception {
        Shop s = shop();
        String appointment = "BAP" + id();
        String workOrder = "BWO" + id();
        String deposit = offline(s, 10000, appointment).get("recordNo").asString();
        JsonNode paid = link(s, 20000, workOrder);
        pay(paid.get("token").asString(), 20000);

        JsonNode beforeCheckout = data(summary(s, appointment, "?dueMinor=0&currency=CNY"));
        assertThat(beforeCheckout.get("receivedMinor").asLong()).as("取消预约前据此提示订金需线下处理")
                .isEqualTo(10000);

        JsonNode alone = data(summary(s, workOrder, "?dueMinor=30000&currency=CNY"));
        assertThat(alone.get("outstandingMinor").asLong()).isEqualTo(10000);

        JsonNode r = data(summary(s, workOrder, "?dueMinor=30000&currency=CNY&relatedBizNo=" + appointment));
        assertThat(r.get("receivedMinor").asLong()).isEqualTo(30000);
        assertThat(r.get("outstandingMinor").asLong()).isZero();
        assertThat(line(r, deposit).get("bizNo").asString()).isEqualTo(appointment);
        assertThat(line(r, paid.get("payOrderNo").asString()).get("bizNo").asString()).isEqualTo(workOrder);

        assertThat(code(summary(s, workOrder, "?dueMinor=1&currency=CNY&relatedBizNo=" + workOrder)))
                .isEqualTo(10400);
        assertThat(code(summary(s, workOrder, "?dueMinor=1&currency=CNY&relatedBizNo=" + "a".repeat(65))))
                .isEqualTo(10400);
    }
}
