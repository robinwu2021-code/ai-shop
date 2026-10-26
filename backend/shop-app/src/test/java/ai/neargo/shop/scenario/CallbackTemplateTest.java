package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.service.LegacyCouponBoundOrderService;
import ai.neargo.shop.trade.service.LegacyCouponOrderPricingService;
import ai.neargo.shop.trade.service.OrderService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * AC11：统一回调模板每一步的失败分支 + 新地址与旧地址别名（TDD-支付服务-I2渠道统一封装 S7）。
 *
 * <p>此前回调只核「验签过没过、回查付没付」：路径里没有收款商户、金额从不比对、重复通知每次都整套重走 ——
 * 一条验签通过、单号对得上、金额被改过的通知，照样推进成功。每一步也只落 {@code stl_channel_message}，
 * 四向记录里查不到渠道推来过什么。
 */
// 支付服务 I1–I3 的场景共用一个 H2 库（DB_CLOSE_DELAY=-1 的内存库活到 JVM 结束，每个库都是一整套表）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"test", "api"})
@Import(CallbackTemplateTest.FakeChannel.class)
class CallbackTemplateTest {

    static final String CH = "TPLCH";

    @TestConfiguration
    static class FakeChannel {
        /** 报文 {@code good|单号} 验签通过，{@code bad…} 不通过 */
        @Bean
        ChannelCallbackVerifier templateVerifier() {
            return new ChannelCallbackVerifier() {
                @Override
                public String payChannel() {
                    return CH;
                }

                @Override
                public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
                    return rawBody != null && rawBody.startsWith("good|")
                            ? Map.of("out_trade_no", rawBody.substring(5), "sign", "SIG") : null;
                }

                @Override
                public String ackOk() {
                    return "OK-ACK";
                }

                @Override
                public String ackFail() {
                    return "FAIL-ACK";
                }
            };
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @MockitoBean private PayQueryPort channel;
    @MockitoBean(extraInterfaces = {LegacyCouponOrderPricingService.class, LegacyCouponBoundOrderService.class})
    private OrderService orders;

    private String no;

    @BeforeEach
    void payment() {
        no = "OT-TPL-" + UUID.randomUUID().toString().substring(0, 8);
        ledger.open(new SettlePort.PaymentOpen(no, "U-TPL", null, CH, 1990, "callback template"));
        jdbc.update("UPDATE stl_payment SET pay_merchant_no='PM-1' WHERE out_trade_no=?", no);
        clearInvocations(orders);
    }

    private String send(String path, String body) throws Exception {
        // 带上真实的安全过滤链：新地址要与旧地址一样不走 Bearer、靠验签
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"))
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> lastLog() {
        return jdbc.queryForMap("SELECT * FROM pay_callback_log WHERE pay_channel=? ORDER BY id DESC LIMIT 1", CH);
    }

    /** TINYINT(1)：H2 给 0 / 1，MySQL 驱动给 Boolean */
    private static boolean flag(Object v) {
        return v instanceof Boolean b ? b : ((Number) v).intValue() == 1;
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM stl_payment WHERE out_trade_no=?", String.class, no);
    }

    @Test
    @DisplayName("★★★ 验签不过（新地址）：先落记录再拒，不回查、不入账；只存原文指纹")
    void badSignature() throws Exception {
        assertThat(send("/pay/callback/" + CH + "/pay/PM-1", "bad|" + no)).isEqualTo("FAIL-ACK");
        verify(channel, never()).query(anyString(), anyString());
        assertThat(lastLog()).containsEntry("verify_result", "FAILED").containsEntry("outcome", "REJECTED")
                .containsEntry("route_style", "PATH").containsEntry("notify_type", "PAY")
                .containsEntry("path_ref", "PM-1").containsEntry("remote_ip", "203.0.113.7")
                .containsEntry("ack_body", "FAIL-ACK");
        assertThat((String) lastLog().get("body_masked")).contains("sha256=").contains("未验签");
    }

    @Test
    @DisplayName("★★★ 路径里的收款商户与这笔交易的不符：拒，不回查 —— 商户 A 的通知不能推进商户 B 的单")
    void merchantMismatch() throws Exception {
        assertThat(send("/pay/callback/" + CH + "/pay/PM-OTHER", "good|" + no)).isEqualTo("FAIL-ACK");
        verify(channel, never()).query(anyString(), anyString());
        verify(orders, never()).markPaid(anyString(), anyString(), anyString());
        assertThat(lastLog()).containsEntry("verify_result", "MERCHANT_MISMATCH").containsEntry("outcome", "REJECTED");
        assertThat(status()).isNotEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("★★★ 渠道确认的金额与这笔不符：不推进成功，记下不符 —— 此前回查只看「付没付」")
    void amountMismatch() throws Exception {
        when(channel.query(CH, no)).thenReturn(new PayQueryPort.Result(true, true, true, 1000, "TX-1", "CNY"));
        assertThat(send("/pay/callback/" + CH + "/pay/PM-1", "good|" + no)).isEqualTo("FAIL-ACK");
        verify(orders, never()).markPaid(anyString(), anyString(), anyString());
        assertThat(lastLog()).containsEntry("outcome", "REJECTED").containsEntry("verify_result", "PASSED");
        assertThat(flag(lastLog().get("amount_matched"))).as("金额不符要记下").isFalse();
        assertThat(status()).isNotEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("★★★ 入账一次；渠道重推同一报文：回成功、不再入账、记成 DUPLICATE")
    void duplicate() throws Exception {
        when(channel.query(CH, no)).thenReturn(new PayQueryPort.Result(true, true, true, 1990, "TX-9", "CNY"));
        assertThat(send("/pay/callback/" + CH + "/pay/PM-1", "good|" + no)).isEqualTo("OK-ACK");
        assertThat(lastLog()).containsEntry("outcome", "APPLIED")
                .containsEntry("channel_trade_no", "TX-9").containsEntry("out_trade_no", no);
        assertThat(flag(lastLog().get("amount_matched"))).isTrue();
        assertThat(status()).isEqualTo("SUCCESS");

        assertThat(send("/pay/callback/" + CH + "/pay/PM-1", "good|" + no)).isEqualTo("OK-ACK");
        assertThat(lastLog()).containsEntry("outcome", "DUPLICATE");
        verify(orders, times(1)).markPaid(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★ 旧地址别名照常可用，记成 LEGACY")
    void legacyAliasStillWorks() throws Exception {
        when(channel.query(CH, no)).thenReturn(new PayQueryPort.Result(true, true, true, 1990, "TX-L", "CNY"));
        assertThat(send("/pay/callback/" + CH, "good|" + no)).isEqualTo("OK-ACK");
        assertThat(lastLog()).containsEntry("route_style", "LEGACY").containsEntry("path_ref", "-")
                .containsEntry("outcome", "APPLIED");
        verify(orders, times(1)).markPaid(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★ 不支持的通知类型、未知通道：回失败，不落记录")
    void unknownTypeOrChannel() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM pay_callback_log", Long.class);
        assertThat(send("/pay/callback/" + CH + "/payout/PM-1", "good|" + no)).isEqualTo("FAIL");
        assertThat(send("/pay/callback/NOPE/pay/PM-1", "good|" + no)).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_callback_log", Long.class)).isEqualTo(before);
    }
}
