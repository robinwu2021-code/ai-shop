package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.port.PayQueryPortImpl;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC8：现有渠道的收款、查单、退款都走统一管线，每一次调用都在 {@code pay_channel_call_log} 留下一行
 * （TDD-支付服务-I2渠道统一封装 S4）。
 *
 * <p>此前渠道调用只有 {@code stl_channel_message} 的零散报文：哪次调用、第几次发送、走的什么容错策略、
 * 结果被归成哪一态，都没有记录 —— 用户报障拿着单号来，查不到渠道那一侧发生了什么。
 */
// 支付服务 I1–I3 的场景共用一个 H2 库（DB_CLOSE_DELAY=-1 的内存库活到 JVM 结束，每个库都是一整套表）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class ChannelCallLogFlowTest {

    @Autowired private SettlePort settle;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private RefundExecutionService refunds;
    /** 真实现：测试环境里 {@code PayQueryPort} 有一个 @Primary 的可编排假件 */
    @Autowired private PayQueryPortImpl query;
    @Autowired private TestPayGateway bank;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ AC8 收款 → 查单 → 退款：三次渠道调用各留一行调用记录，四态归类与通道单号都在")
    void payQueryRefundEachLeaveACallLogRow() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        var route = new PaymentRoute("e-" + id, "s-" + id, "pm-" + id, "TEST", "sub-mch-" + id, "pm-" + id + ":1",
                "DIRECT");
        var open = new PaymentOpen("ord-" + id, "u-" + id, null, "TEST", 1234, "goods", "CNY",
                List.of(new PaymentStore("sub-" + id, route)));

        var init = settle.initPayment(open);
        assertThat(init.success()).as(init.message()).isTrue();
        String outTradeNo = init.outTradeNo();
        Map<String, Object> pay = row("PAY", outTradeNo);
        assertThat(pay).containsEntry("pay_channel", "TEST").containsEntry("access_mode", "PLATFORM_SUB")
                .containsEntry("outcome_class", "PENDING").containsEntry("request_currency", "CNY")
                .containsEntry("channel_amount_text", "12.34").containsEntry("source_type", "INTERNAL");
        assertThat(pay.get("channel_trade_no")).isNotNull();

        bank.markPaid(outTradeNo);
        assertThat(query.query("TEST", outTradeNo).paid()).isTrue();
        assertThat(row("QUERY", outTradeNo)).containsEntry("outcome_class", "SUCCEEDED");

        String tradeNo = (String) pay.get("channel_trade_no");
        ledger.settle(new PaymentSettled(outTradeNo, "TEST", tradeNo, 1234, System.currentTimeMillis(), "CNY"));
        String paymentNo = jdbc.queryForObject(
                "SELECT payment_no FROM stl_payment WHERE out_trade_no=? AND direction='PAY'", String.class, outTradeNo);
        var refund = refunds.request(new RefundRequest("rx-" + id, "as-" + id, "sub-" + id, paymentNo, 300, "CNY",
                "七天无理由"));
        assertThat(refund.status().name()).as(refund.errorCode()).isEqualTo("ACCEPTED");
        String outRefundNo = jdbc.queryForObject(
                "SELECT out_trade_no FROM stl_payment WHERE refund_execution_no=?", String.class, "rx-" + id);
        assertThat(row("REFUND", outRefundNo)).containsEntry("outcome_class", "PENDING")
                .containsEntry("request_amount_minor", 300L);

        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT trace_id) FROM pay_channel_call_log WHERE out_trade_no IN (?,?)",
                Integer.class, outTradeNo, outRefundNo)).as("每次调用各自一个追踪号").isEqualTo(3);
    }

    private Map<String, Object> row(String operation, String outNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM pay_channel_call_log WHERE operation=? AND out_trade_no=?", operation, outNo);
        assertThat(rows).as("%s %s 应有且只有一行调用记录", operation, outNo).hasSize(1);
        return rows.get(0);
    }
}
