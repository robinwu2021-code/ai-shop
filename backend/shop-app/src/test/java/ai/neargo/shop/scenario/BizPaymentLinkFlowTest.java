package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * 门店收银台与付款链接（TDD-门店收银台与付款链接 L1–L7）：生成链接带卡片字段、链接记录四种状态、
 * 再次分享换发令牌、作废（已付不能作废）、顾客端失效与交易号后 6 位。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BizPaymentLinkFlowTest {

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
                        .content("{\"name\":\"付款链接" + id() + "\",\"subject\":\"INDIVIDUAL_BIZ\","
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
        String pm = "PM-LK-" + id();
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
        bindings.bind(storeId, "TEST", merchantId, "OP-LK");
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

    private JsonNode link(Shop s, long amount, String subject) throws Exception {
        return data(as(s, post("/biz/payment/collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":" + amount + ",\"subject\":\"" + subject + "\",\"expireMinutes\":30}")));
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

    private void expire(String payOrderNo) {
        // 库里是 UTC；不用数据库的 CURRENT_TIMESTAMP（那是本机时区）
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        jdbc.update("UPDATE pay_order SET expire_at = ? WHERE pay_order_no=?", past, payOrderNo);
        jdbc.update("UPDATE pay_checkout_session SET expire_at = ? WHERE pay_order_id ="
                + " (SELECT id FROM pay_order WHERE pay_order_no=?)", past, payOrderNo);
    }

    private static List<String> nos(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("payOrderNo").asString()));
        return out;
    }

    private static JsonNode row(JsonNode page, String no) {
        for (JsonNode i : page.get("items")) {
            if (no.equals(i.get("payOrderNo").asString())) {
                return i;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★★★ L1 生成链接：响应带金额、币种、说明、店名，端上画卡片不必再查")
    void collectReturnsCardFields() throws Exception {
        Shop s = shop();
        JsonNode c = link(s, 4500, "订金");
        assertThat(c.get("payOrderNo").asString()).isNotBlank();
        assertThat(c.get("token").asString()).isNotBlank();
        assertThat(c.get("checkoutUrl").asString()).endsWith("/c/" + c.get("token").asString());
        assertThat(c.get("amountMinor").asLong()).isEqualTo(4500);
        assertThat(c.get("currency").asString()).isEqualTo("CNY");
        assertThat(c.get("subject").asString()).isEqualTo("订金");
        assertThat(c.get("storeName").asString()).isNotBlank();
        // 有效期按给的 30 分钟，不被收银令牌默认 15 分钟截短
        assertThat(c.get("expireAt").asLong() - System.currentTimeMillis()).isGreaterThan(25 * 60_000L);
        // 超过上限（默认 1440 分钟）拒绝
        assertThat(code(as(s, post("/biz/payment/collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100,\"expireMinutes\":1441}")))).isNotZero();
        // 顾客端会话视图带说明与截止时间
        JsonNode view = json.readTree(mvc().perform(get("/checkout/v1/sessions/" + c.get("token").asString()))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(view.get("subject").asString()).isEqualTo("订金");
        // 库里 DATETIME(3) 会把亚毫秒进位，差 1 毫秒不算
        assertThat(view.get("expireAt").asLong()).isCloseTo(c.get("expireAt").asLong(), org.assertj.core.data.Offset.offset(5L));
    }

    @Test
    @DisplayName("★★★ L3 链接记录：待付款 / 已付款 / 已过期 / 已作废各一条，按状态筛，游标翻页，只看本店的付款链接")
    void linksListPendingPaidExpiredVoided() throws Exception {
        assertThat(call(null, null, get("/biz/payment/links")).getStatus()).isEqualTo(401);
        Shop s = shop();
        Shop other = shop();
        String pending = link(s, 100, "待付").get("payOrderNo").asString();
        JsonNode p = link(s, 200, "已付");
        String paid = p.get("payOrderNo").asString();
        pay(p.get("token").asString(), 200);
        String expired = link(s, 300, "过期").get("payOrderNo").asString();
        expire(expired);
        String voided = link(s, 400, "作废").get("payOrderNo").asString();
        data(as(s, post("/biz/payment/links/" + voided + "/void").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"顾客改用现金\"}")));
        String theirs = link(other, 500, "别家").get("payOrderNo").asString();
        // 别的来源的单（静态收款码）不是付款链接，不进
        jdbc.update("UPDATE pay_order SET biz_type='CHECKOUT_QR' WHERE pay_order_no=?", link(s, 600, "码").get("payOrderNo").asString());

        JsonNode all = data(as(s, get("/biz/payment/links").param("size", "50")));
        assertThat(nos(all)).containsExactly(voided, expired, paid, pending).doesNotContain(theirs);
        assertThat(row(all, pending).get("status").asString()).isEqualTo("PENDING");
        assertThat(row(all, paid).get("status").asString()).isEqualTo("PAID");
        assertThat(row(all, paid).get("channel").asString()).isEqualTo("TEST");
        assertThat(row(all, paid).get("paidAt").isNull()).isFalse();
        assertThat(row(all, expired).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(row(all, voided).get("status").asString()).isEqualTo("VOIDED");
        assertThat(row(all, voided).get("voidedBy").asString()).isNotBlank();
        assertThat(row(all, pending).get("subject").asString()).isEqualTo("待付");
        assertThat(row(all, pending).get("amountMinor").asLong()).isEqualTo(100);
        assertThat(row(all, pending).get("operatorNo").asString()).isNotBlank();
        assertThat(row(all, pending).get("expireAt").asLong()).isGreaterThan(System.currentTimeMillis());

        for (String[] st : new String[][] {{"PENDING", pending}, {"PAID", paid}, {"EXPIRED", expired}, {"VOIDED", voided}}) {
            assertThat(nos(data(as(s, get("/biz/payment/links").param("status", st[0]))))).as(st[0]).containsExactly(st[1]);
        }

        JsonNode first = data(as(s, get("/biz/payment/links").param("size", "2")));
        assertThat(nos(first)).containsExactly(voided, expired);
        JsonNode second = data(as(s, get("/biz/payment/links").param("size", "2")
                .param("cursor", first.get("nextCursor").asString())));
        assertThat(nos(second)).containsExactly(paid, pending);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("★★★ L4 再次分享：换发新令牌，旧令牌失效，新令牌可付，支付单号不变；已付的不能再分享")
    void reissueRevokesOldTokenAndKeepsSameOrder() throws Exception {
        Shop s = shop();
        JsonNode first = link(s, 880, "再分享");
        String no = first.get("payOrderNo").asString();
        String oldToken = first.get("token").asString();
        JsonNode again = data(as(s, post("/biz/payment/links/" + no + "/reissue")));
        assertThat(again.get("payOrderNo").asString()).isEqualTo(no);
        String newToken = again.get("token").asString();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(again.get("amountMinor").asLong()).isEqualTo(880);
        assertThat(again.get("subject").asString()).isEqualTo("再分享");
        // 「从现在起按原时长」：刚生成就换发，新截止时间不能比原来早出一截（曾因时区口径被压成 1 分钟）
        assertThat(again.get("expireAt").asLong())
                .as("reissue keeps the original duration").isGreaterThanOrEqualTo(first.get("expireAt").asLong() - 120_000);

        assertThat(mvc().perform(get("/checkout/v1/sessions/" + oldToken)).andReturn().getResponse().getStatus())
                .isEqualTo(410);
        pay(newToken, 880);
        JsonNode row = row(data(as(s, get("/biz/payment/links"))), no);
        assertThat(row.get("status").asString()).isEqualTo("PAID");
        assertThat(code(as(s, post("/biz/payment/links/" + no + "/reissue")))).isNotZero();

        Shop other = shop();
        String pending = link(s, 10, "x").get("payOrderNo").asString();
        assertThat(code(call(other.token(), other.storeNo(), post("/biz/payment/links/" + pending + "/reissue")))).isNotZero();
    }

    @Test
    @DisplayName("★★★ L5 作废：待付款的关单、令牌失效；已付款的不作废，返回 PAID")
    void voidClosesPendingAndRefusesPaid() throws Exception {
        Shop s = shop();
        JsonNode p = link(s, 300, "作废");
        String no = p.get("payOrderNo").asString();
        JsonNode v = data(as(s, post("/biz/payment/links/" + no + "/void").contentType(MediaType.APPLICATION_JSON)
                .content("{}")));
        assertThat(v.get("status").asString()).isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE pay_order_no=?", String.class, no))
                .isEqualTo("CLOSED");
        assertThat(mvc().perform(get("/checkout/v1/sessions/" + p.get("token").asString())).andReturn().getResponse()
                .getStatus()).isEqualTo(410);

        JsonNode q = link(s, 500, "已付");
        String paid = q.get("payOrderNo").asString();
        pay(q.get("token").asString(), 500);
        JsonNode r = data(as(s, post("/biz/payment/links/" + paid + "/void")));
        assertThat(r.get("status").asString()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE pay_order_no=?", String.class, paid))
                .isEqualTo("SUCCEEDED");

        Shop other = shop();
        String pending = link(s, 10, "x").get("payOrderNo").asString();
        assertThat(code(call(other.token(), other.storeNo(), post("/biz/payment/links/" + pending + "/void")))).isNotZero();
    }

    @Test
    @DisplayName("★★ L6 顾客端：过期或作废的令牌回「已失效」（410，不是 404）；不存在的令牌仍是 404")
    void expiredOrVoidedTokenSaysInvalid() throws Exception {
        Shop s = shop();
        JsonNode e = link(s, 100, "过期");
        expire(e.get("payOrderNo").asString());
        MockHttpServletResponse r = mvc().perform(get("/checkout/v1/sessions/" + e.get("token").asString()))
                .andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(410);
        assertThat(json.readTree(r.getContentAsString()).get("code").asInt()).isEqualTo(70093);

        JsonNode v = link(s, 100, "作废");
        data(as(s, post("/biz/payment/links/" + v.get("payOrderNo").asString() + "/void")));
        assertThat(mvc().perform(get("/checkout/v1/sessions/" + v.get("token").asString())).andReturn().getResponse()
                .getStatus()).isEqualTo(410);

        assertThat(mvc().perform(get("/checkout/v1/sessions/nosuchtoken")).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("★★ L6 付款结果带渠道交易号后 6 位（成功页给顾客对账）")
    void resultCarriesTradeNoSuffix() throws Exception {
        Shop s = shop();
        JsonNode c = link(s, 700, "尾号");
        String token = c.get("token").asString();
        JsonNode before = json.readTree(mvc().perform(get("/checkout/v1/sessions/" + token + "/result")).andReturn()
                .getResponse().getContentAsString()).get("data");
        assertThat(before.get("result").asString()).isEqualTo("PROCESSING");
        assertThat(before.get("tradeNoSuffix") == null || before.get("tradeNoSuffix").isNull()).isTrue();
        String tradeNo = pay(token, 700);
        JsonNode after = json.readTree(mvc().perform(get("/checkout/v1/sessions/" + token + "/result")).andReturn()
                .getResponse().getContentAsString()).get("data");
        assertThat(after.get("result").asString()).isEqualTo("SUCCEEDED");
        assertThat(after.get("tradeNoSuffix").asString()).isEqualTo(tradeNo.substring(tradeNo.length() - 6));
    }

    @Test
    @DisplayName("★★ L7 收银台权限：店长默认可用，店员默认不可用；链接记录店长店员都能看")
    void managerCanCollectClerkCannotByDefault() {
        assertThat(BizPerms.can(Set.of(BizPerms.MANAGER), BizPerms.PAY_COLLECT)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.PAY_COLLECT)).isFalse();
        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.PAY_ORDER_VIEW)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.OWNER), BizPerms.PAY_COLLECT)).isTrue();
    }
}
