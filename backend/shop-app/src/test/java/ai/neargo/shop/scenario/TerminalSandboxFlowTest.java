package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.terminal.service.ManagerCodeService;
import ai.neargo.shop.pay.terminal.service.TerminalService;
import ai.neargo.shop.pay.terminal.service.TerminalService.Activated;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
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
 * POS 终端沙箱全链路（pay-guard 开发计划 M6.4 验收）：登记 → 激活（设备密钥只此一次）→ 签名建单 → 刷卡（TEST）→ 结果不明 →
 * 渠道那边付了 → 轮询查单补账 → 日结匹配；撤销要店长授权码；挂失后下一次请求 401。
 * 终端侧的签名在本类里按 API 清单 §7 独立实现（与开放 API 同一条待签串，AppId 换终端号）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.secret.env=SANDBOX",
        "shop.secret.master-keys[0].alias=test-pay-mk-1",
        "shop.secret.master-keys[0].namespace=PAY",
        "shop.secret.master-keys[0].material=dGVzdC1vbmx5LW1hc3Rlci1rZXktMDEyMzQ1Njc4OSE=",
        "shop.secret.master-keys[0].active=true",
        "shop.pay.terminal.sandbox-pay-method=TEST_PAY"})
@ActiveProfiles("test")
class TerminalSandboxFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private TerminalService terminals;
    @Autowired private ManagerCodeService managerCodes;
    @Autowired private PayMerchantBindingService bindings;
    /** 测试档的渠道查单是可编程的：「渠道那边付了」= 让查单答已付 */
    @Autowired private FakePayQueryPort channel;

    private MockMvc mvc;
    private Long storeId;
    private String sn;

    @BeforeEach
    void world() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        sn = "SN-" + UUID.randomUUID().toString().substring(0, 8);
        var bound = jdbc.queryForList("SELECT id, store_id FROM pay_merchant WHERE pay_merchant_no LIKE 'PM-TM-%'"
                + " AND store_id IS NOT NULL ORDER BY id LIMIT 1");
        if (!bound.isEmpty()) {
            storeId = ((Number) bound.get(0).get("store_id")).longValue();
            terminals.register(sn, storeId, ((Number) bound.get(0).get("id")).longValue(), "SUNMI-P3", 5000, "OPS-A");
            return;
        }
        storeId = jdbc.queryForObject("SELECT id FROM mch_store WHERE id NOT IN (SELECT store_id FROM pay_merchant"
                + " WHERE store_id IS NOT NULL) ORDER BY id LIMIT 1", Long.class);
        String pm = "PM-TM-" + UUID.randomUUID().toString().substring(0, 8);
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
        bindings.bind(storeId, "TEST", merchantId, "OP-TM");
        terminals.register(sn, storeId, merchantId, "SUNMI-P3", 5000, "OPS-A");
    }

    private void channelPaid(String outTradeNo, long amountMinor) {
        channel.answerForTrade(outTradeNo, new PayQueryPort.Result(true, true, true, amountMinor, "TESTTX-" + outTradeNo));
    }

    private Activated activate() throws Exception {
        String code = terminals.issueActivationCode(sn, "OPS-A");
        JsonNode a = data(mvc.perform(post("/terminal/pay/v1/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"activationCode\":\"" + code + "\",\"deviceFingerprint\":\"fp-1\",\"appVersion\":\"1.0\"}"))
                .andReturn().getResponse());
        assertThat(code(mvc.perform(post("/terminal/pay/v1/activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"activationCode\":\"" + code + "\",\"deviceFingerprint\":\"fp-1\"}")).andReturn().getResponse()))
                .as("激活码单次").isEqualTo(10401);
        return new Activated(a.get("terminalSn").asString(), a.get("storeId").asLong(), a.get("deviceSecret").asString(),
                a.get("keySerial").asString());
    }

    /** 终端侧签名：与开放 API 同一条待签串，第 4 行是终端号；密钥 = 设备密钥串的 UTF-8 字节 */
    private MockHttpServletResponse call(Activated dev, MockHttpServletRequestBuilder b, String method, String path, String body)
            throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String sig = OpenApiFlowTest.sign(dev.deviceSecret(), method, path, Map.of(), dev.terminalSn(), ts, nonce, body);
        return mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(body).header("X-Terminal-Sn", dev.terminalSn())
                .header("X-Timestamp", String.valueOf(ts)).header("X-Nonce", nonce).header("X-Signature", sig)
                .header("X-Operator", "CASHIER-1")).andReturn().getResponse();
    }

    private MockHttpServletResponse post2(Activated dev, String path, String body) throws Exception {
        return call(dev, post(path), "POST", path, body);
    }

    private MockHttpServletResponse get2(Activated dev, String path) throws Exception {
        return call(dev, get(path), "GET", path, "");
    }

    private JsonNode data(MockHttpServletResponse r) throws Exception {
        JsonNode n = json.readTree(r.getContentAsString());
        assertThat(n.get("code").asInt()).as(r.getContentAsString()).isZero();
        return n.get("data");
    }

    private int code(MockHttpServletResponse r) throws Exception {
        return r.getStatus() == 401 ? 10401 : json.readTree(r.getContentAsString()).get("code").asInt();
    }

    @Test
    @DisplayName("★★★ 沙箱：激活 → 签名建单 → 刷卡（TEST）结果不明 → 渠道付了 → 轮询查单补账 → 日结匹配；响应带设备密钥签名")
    void activateChargeUnknownQueryBatch() throws Exception {
        Activated dev = activate();
        assertThat(dev.deviceSecret()).hasSizeGreaterThanOrEqualTo(43);

        MockHttpServletResponse created = post2(dev, "/terminal/pay/v1/payments",
                "{\"outOrderNo\":\"POS-" + sn + "\",\"amountMinor\":1880,\"currency\":\"CNY\",\"subject\":\"门店收款\"}");
        assertThat(created.getHeader("X-Signature")).as("响应用设备密钥签（P2）").isNotBlank();
        String no = data(created).get("payOrderNo").asString();

        JsonNode charged = data(post2(dev, "/terminal/pay/v1/payments/" + no + "/card", "{\"encryptedCardData\":\"ENC==\"}"));
        assertThat(charged.get("status").asString()).as("同步结果不明 —— 不判失败").isEqualTo("UNKNOWN");

        String outTradeNo = jdbc.queryForObject("SELECT s.out_trade_no FROM stl_payment s JOIN pay_order o ON o.id=s.pay_order_id"
                + " WHERE o.pay_order_no=? AND s.direction='PAY' ORDER BY s.id DESC LIMIT 1", String.class, no);
        channelPaid(outTradeNo, 1880);
        assertThat(data(get2(dev, "/terminal/pay/v1/payments/" + no)).get("status").asString())
                .as("轮询查单补账").isEqualTo("SUCCEEDED");

        String day = LocalDate.now(ZoneOffset.UTC).toString();
        JsonNode batch = data(post2(dev, "/terminal/pay/v1/batches",
                "{\"bizDate\":\"" + day + "\",\"currency\":\"CNY\",\"count\":1,\"amountMinor\":1880}"));
        assertThat(batch.get("status").asString()).isEqualTo("MATCHED");
        assertThat(data(post2(dev, "/terminal/pay/v1/batches",
                "{\"bizDate\":\"" + day + "\",\"currency\":\"CNY\",\"count\":2,\"amountMinor\":3760}")).get("status").asString())
                .as("离线刷卡未上送之类 —— 对不上").isEqualTo("MISMATCHED");
    }

    @Test
    @DisplayName("★★★ 撤销要店长授权码：码不对拒；码对了走退款单、店长记在 authorized_by；超单终端日限额拒")
    void voidNeedsManagerCodeAndDailyLimit() throws Exception {
        Activated dev = activate();
        String no = data(post2(dev, "/terminal/pay/v1/payments",
                "{\"outOrderNo\":\"POS-V-" + sn + "\",\"amountMinor\":3000,\"currency\":\"CNY\"}")).get("payOrderNo").asString();
        post2(dev, "/terminal/pay/v1/payments/" + no + "/card", "{\"encryptedCardData\":\"ENC==\"}");
        channelPaid(jdbc.queryForObject("SELECT s.out_trade_no FROM stl_payment s JOIN pay_order o ON o.id=s.pay_order_id"
                + " WHERE o.pay_order_no=? AND s.direction='PAY' ORDER BY s.id DESC LIMIT 1", String.class, no), 3000);
        data(get2(dev, "/terminal/pay/v1/payments/" + no));
        managerCodes.set(storeId, 77L, "246810");

        assertThat(code(post2(dev, "/terminal/pay/v1/payments/" + no + "/void",
                "{\"amountMinor\":1000,\"managerCode\":\"000000\"}"))).as("码不对").isNotZero();
        JsonNode refund = data(post2(dev, "/terminal/pay/v1/payments/" + no + "/void",
                "{\"amountMinor\":1000,\"managerCode\":\"246810\",\"reason\":\"顾客取消\"}"));
        assertThat(refund.get("authorizedBy").asString()).isEqualTo("MANAGER:77");
        assertThat(code(post2(dev, "/terminal/pay/v1/payments/" + no + "/refunds",
                "{\"amountMinor\":4500,\"managerCode\":\"246810\"}"))).as("日限额 5000，已退 1000").isNotZero();
    }

    @Test
    @DisplayName("★★★ 挂失：设备密钥吊销，下一次签名请求 401；没签名、签错、重放一律 401 且不进 controller")
    void lostTerminalIsRejected() throws Exception {
        Activated dev = activate();
        assertThat(get2(dev, "/terminal/pay/v1/payments/PYO-NOPE").getStatus()).isNotEqualTo(401);
        Activated forged = new Activated(dev.terminalSn(), dev.storeId(), "not-the-device-secret-0123456789abcdefgh", null);
        assertThat(get2(forged, "/terminal/pay/v1/payments/PYO-NOPE").getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/terminal/pay/v1/payments/PYO-NOPE")).andReturn().getResponse().getStatus()).isEqualTo(401);

        long ts = System.currentTimeMillis();
        String path = "/terminal/pay/v1/payments/PYO-NOPE";
        String sig = OpenApiFlowTest.sign(dev.deviceSecret(), "GET", path, Map.of(), dev.terminalSn(), ts, "replay-nonce-01", "");
        MockHttpServletRequestBuilder once = get(path).header("X-Terminal-Sn", dev.terminalSn())
                .header("X-Timestamp", String.valueOf(ts)).header("X-Nonce", "replay-nonce-01").header("X-Signature", sig);
        assertThat(mvc.perform(once).andReturn().getResponse().getStatus()).isNotEqualTo(401);
        assertThat(mvc.perform(get(path).header("X-Terminal-Sn", dev.terminalSn()).header("X-Timestamp", String.valueOf(ts))
                .header("X-Nonce", "replay-nonce-01").header("X-Signature", sig)).andReturn().getResponse().getStatus())
                .as("同一 nonce 重放").isEqualTo(401);

        terminals.reportLost(sn, "OPS-B", "设备丢失");

        assertThat(get2(dev, "/terminal/pay/v1/payments/PYO-NOPE").getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_api_request_log WHERE entry='TERMINAL_API'"
                + " AND outcome='REJECTED_AUTH'", Long.class)).isPositive();
    }

    @Test
    @DisplayName("★★ 店长授权码连续输错 5 次锁定：锁定期内对的码也不认；重置后恢复")
    void managerCodeLocksAfterFiveFailures() {
        managerCodes.set(storeId, 88L, "135790");
        for (int i = 0; i < 5; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> managerCodes.verify(storeId, "000000"))
                    .isInstanceOf(ai.neargo.shop.common.BizException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> managerCodes.verify(storeId, "135790"))
                .as("锁定中").isInstanceOf(ai.neargo.shop.common.BizException.class);
        managerCodes.set(storeId, 88L, "135790");
        assertThat(managerCodes.verify(storeId, "135790")).isEqualTo(88L);
    }
}
