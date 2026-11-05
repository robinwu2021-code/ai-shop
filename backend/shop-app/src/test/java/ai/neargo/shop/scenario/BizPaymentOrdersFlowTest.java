package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import java.util.ArrayList;
import java.util.List;
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
 * 商家端「收款」Tab（TDD-B端收款订单与商城接口对齐 P1，A1–A8）：支付订单列表、时段汇总、查找一笔、详情。
 * 顾客付了钱 → 商家在本店的收款列表里查得到；没付成的不进列表；看不到别家的。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BizPaymentOrdersFlowTest {

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
                        .content("{\"name\":\"收款列表" + id() + "\",\"subject\":\"INDIVIDUAL_BIZ\","
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
        String pm = "PM-BO-" + id();
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
        bindings.bind(storeId, "TEST", merchantId, "OP-BO");
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

    /** 代客收款出一张单；pay=true 时顾客经收银台付掉。返回 [支付单号, 渠道交易号] */
    private String[] collect(Shop s, long amount, boolean pay) throws Exception {
        JsonNode issued = data(call(s.token(), s.storeNo(), post("/biz/payment/collection")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":" + amount + ",\"subject\":\"测\"}")));
        String no = issued.get("payOrderNo").asString();
        if (!pay) {
            return new String[] {no, null};
        }
        String token = issued.get("token").asString();
        JsonNode started = json.readTree(mvc().perform(post("/checkout/v1/sessions/" + token + "/pay")
                .contentType(MediaType.APPLICATION_JSON).content("{\"payMethod\":\"TEST_PAY\"}")).andReturn().getResponse()
                .getContentAsString()).get("data");
        String tradeNo = "T" + id() + id();
        settle.settlePayment(new SettlePort.PaymentSettled(started.get("outTradeNo").asString(), "TEST", tradeNo,
                amount, System.currentTimeMillis()));
        return new String[] {no, tradeNo};
    }

    private static List<String> nos(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("payOrderNo").asString()));
        return out;
    }

    @Test
    @DisplayName("★★★ A1 顾客付了的进本店收款列表（来源、渠道、金额、状态齐全）；只开了单没付的不进；别家看不到")
    void listShowsPaidOnlyAndIsStoreScoped() throws Exception {
        assertThat(call(null, null, get("/biz/payment/orders")).getStatus()).isEqualTo(401);
        Shop s = shop();
        Shop other = shop();
        String paid = collect(s, 2600, true)[0];
        String abandoned = collect(s, 500, false)[0];
        String theirs = collect(other, 700, true)[0];

        JsonNode page = data(as(s, get("/biz/payment/orders")));
        assertThat(nos(page)).contains(paid).doesNotContain(abandoned, theirs);
        JsonNode row = page.get("items").get(0);
        assertThat(row.get("payOrderNo").asString()).isEqualTo(paid);
        assertThat(row.get("source").asString()).isEqualTo("COLLECT");
        assertThat(row.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(row.get("amountMinor").asLong()).isEqualTo(2600);
        assertThat(row.get("channel").asString()).isEqualTo("TEST");
        assertThat(row.get("succeededAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("★★★ A2 筛选：来源、状态、金额区间；游标翻页不重不漏")
    void filtersAndCursor() throws Exception {
        Shop s = shop();
        String a = collect(s, 1000, true)[0];
        String b = collect(s, 2000, true)[0];
        String c = collect(s, 3000, true)[0];

        assertThat(nos(data(as(s, get("/biz/payment/orders").param("source", "QR_CODE"))))).isEmpty();
        assertThat(nos(data(as(s, get("/biz/payment/orders").param("source", "COLLECT"))))).contains(a, b, c);
        assertThat(nos(data(as(s, get("/biz/payment/orders").param("status", "FAILED"))))).isEmpty();
        assertThat(nos(data(as(s, get("/biz/payment/orders").param("amountMin", "1500").param("amountMax", "2500")))))
                .containsExactly(b);

        JsonNode first = data(as(s, get("/biz/payment/orders").param("size", "2")));
        assertThat(nos(first)).hasSize(2);
        String cursor = first.get("nextCursor").asString();
        assertThat(cursor).isNotBlank();
        collect(s, 4000, true);   // 翻页期间又进来一笔，不能挤乱第二页
        JsonNode second = data(as(s, get("/biz/payment/orders").param("size", "2").param("cursor", cursor)));
        List<String> all = new ArrayList<>(nos(first));
        all.addAll(nos(second));
        assertThat(all).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    @DisplayName("★★ A3 时段汇总：收款笔数与金额、按渠道分组、净收款")
    void summary() throws Exception {
        Shop s = shop();
        collect(s, 1200, true);
        collect(s, 800, true);
        collect(s, 999, false);
        JsonNode sum = data(as(s, get("/biz/payment/orders/summary")));
        assertThat(sum.get("collected").get("count").asInt()).isEqualTo(2);
        assertThat(sum.get("collected").get("amountMinor").asLong()).isEqualTo(2000);
        assertThat(sum.get("netMinor").asLong()).isEqualTo(2000 - sum.get("refunded").get("amountMinor").asLong());
        assertThat(sum.get("byChannel").get(0).get("channel").asString()).isEqualTo("TEST");
        assertThat(sum.get("byChannel").get(0).get("count").asInt()).isEqualTo(2);
        assertThat(sum.get("currency").asString()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("★★★ A6 顾客说「我付了」：按金额 + 最近 10 分钟找到；按交易号后 6 位找到；少于 6 位拒绝")
    void lookup() throws Exception {
        Shop s = shop();
        String[] p = collect(s, 4200, true);
        collect(s, 4300, true);
        JsonNode byAmount = data(as(s, get("/biz/payment/orders/lookup").param("amountMinor", "4200").param("within", "10m")));
        assertThat(byAmount.get("matches")).hasSize(1);
        assertThat(byAmount.get("matches").get(0).get("payOrderNo").asString()).isEqualTo(p[0]);

        String suffix = p[1].substring(p[1].length() - 6);
        JsonNode byTrade = data(as(s, get("/biz/payment/orders/lookup").param("tradeNoSuffix", suffix)));
        assertThat(byTrade.get("matches").get(0).get("payOrderNo").asString()).isEqualTo(p[0]);

        assertThat(code(as(s, get("/biz/payment/orders/lookup").param("tradeNoSuffix", "123")))).isNotZero();
    }

    @Test
    @DisplayName("★★★ A7 详情：金额与入账、渠道交易号、来源、结算账户、退款记录；别家的单查不到")
    void detail() throws Exception {
        Shop s = shop();
        Shop other = shop();
        String[] p = collect(s, 2600, true);
        data(call(s.token(), s.storeNo(), post("/biz/payment/refund").contentType(MediaType.APPLICATION_JSON)
                .content("{\"payOrderNo\":\"" + p[0] + "\",\"amountMinor\":600,\"reason\":\"少收一件\"}")));

        JsonNode d = data(as(s, get("/biz/payment/orders/" + p[0])));
        assertThat(d.get("row").get("payOrderNo").asString()).isEqualTo(p[0]);
        assertThat(d.get("amounts").get("paidMinor").asLong()).isEqualTo(2600);
        assertThat(d.get("refs").get("channelTradeNo").asString()).isEqualTo(p[1]);
        assertThat(d.get("source").get("kind").asString()).isEqualTo("COLLECT");
        assertThat(d.get("settlement").get("accountMasked").asString()).isEqualTo("****8901");
        assertThat(d.get("settlement").get("expectedDate").isNull()).isFalse();
        assertThat(d.get("refunds")).hasSize(1);
        assertThat(d.get("refunds").get(0).get("amountMinor").asLong()).isEqualTo(600);
        assertThat(d.get("timeline").toString()).contains("CREATED", "PAID", "REFUND_REQUESTED");

        assertThat(code(call(other.token(), other.storeNo(), get("/biz/payment/orders/" + p[0])))).isNotZero();
    }

    @Test
    @DisplayName("★★ A8 待确认的单可以「立即查询结果」，返回最新详情")
    void queryRefreshesPending() throws Exception {
        Shop s = shop();
        String no = collect(s, 300, false)[0];
        JsonNode d = data(call(s.token(), s.storeNo(), post("/biz/payment/orders/" + no + "/query")));
        assertThat(d.get("row").get("payOrderNo").asString()).isEqualTo(no);
    }
}
