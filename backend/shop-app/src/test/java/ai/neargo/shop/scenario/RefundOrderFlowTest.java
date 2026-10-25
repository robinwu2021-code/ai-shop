package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC6：退款单占用可退余额、成功后记账（TDD-支付服务-I3支付单退款流水 S6）。复核关（默认）时直接执行。
 *
 * <p>此前退款只有通道尝试一层（stl_payment REFUND），「这笔单还能退多少」要从几张表里拼；退款成功也不进任何流水。
 */
// 支付服务 I3 的六个场景类共用一个上下文（同一个库）：各自用随机单号，互不干扰；每类一个上下文会把全量测试撑爆堆
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RefundOrderFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private RefundExecutionService executions;
    @Autowired private TestPayGateway bank;
    @Autowired private JdbcTemplate jdbc;

    /** 一张 AGGREGATED、已付 1990 的支付单；返回支付单号 */
    private String paidOrder() {
        String no = "PO-RF-" + UUID.randomUUID().toString().substring(0, 8);
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        var handle = payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store, "门店",
                "主体", "CN", 1990, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", 1990, "goods"));
        String trade = bank.placeOrder(no, 1990);
        bank.markPaid(no);
        settle.settlePayment(new PaymentSettled(no, "TEST", trade, 1990, System.currentTimeMillis(), "CNY"));
        return handle.payOrderNo();
    }

    private Map<String, Object> order(String payOrderNo) {
        return jdbc.queryForMap("SELECT * FROM pay_order WHERE pay_order_no=?", payOrderNo);
    }

    private InternalRefundCmd refund(String payOrderNo, long amount, String operator) {
        String out = "RF-" + UUID.randomUUID().toString().substring(0, 8);
        return new InternalRefundCmd(PaymentPort.MALL_ORDER, payOrderNo, out, "AS-" + out, amount, "七天无理由", operator);
    }

    @Test
    @DisplayName("★★★ 退款：建退款单、占用、执行；渠道确认后已退 +500、占用归零、支付单部分退款、记一张反向凭证")
    void refundReservesThenBooks() {
        String po = paidOrder();
        var cmd = refund(po, 500, "OP-1");
        var handle = payments.requestRefund(cmd);
        assertThat(handle.status()).isEqualTo("DISPATCHED");
        Map<String, Object> pr = jdbc.queryForMap("SELECT * FROM pay_refund WHERE pay_refund_no=?", handle.payRefundNo());
        assertThat(pr).containsEntry("requested_by", "OP-1").containsEntry("source_type", "BIZ")
                .containsEntry("biz_refund_no", cmd.bizRefundNo()).containsEntry("amount_minor", 500L);
        assertThat(order(po)).containsEntry("reserved_refund_minor", 500L).containsEntry("refunded_minor", 0L);

        String provider = jdbc.queryForObject("SELECT trade_no FROM stl_payment WHERE out_trade_no=?", String.class,
                cmd.outRefundNo());
        assertThat(executions.confirm(cmd.outRefundNo(), "TEST", 500, "CNY", provider).status().name())
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE pay_refund_no=?", String.class,
                handle.payRefundNo())).isEqualTo("SUCCEEDED");
        assertThat(order(po)).containsEntry("refunded_minor", 500L).containsEntry("reserved_refund_minor", 0L)
                .containsEntry("status", "PARTIALLY_REFUNDED");
        assertThat(jdbc.queryForList("SELECT e.account, e.direction FROM pay_ledger_entry e JOIN pay_ledger_voucher v"
                + " ON v.id = e.pay_ledger_voucher_id WHERE v.voucher_type='REFUND_SUCCEEDED' AND e.pay_order_id=?"
                + " ORDER BY e.line_no", order(po).get("id"))).extracting(e -> e.get("account") + " " + e.get("direction"))
                .containsExactly("MERCHANT_PAYABLE DR", "PLATFORM_CHANNEL CR");
    }

    @Test
    @DisplayName("★★★ 可退余额 = 实收 − 已退 − 在途占用：1500 在途时再退 600 被拒")
    void inFlightReservationCountsAgainstRefundable() {
        String po = paidOrder();
        payments.requestRefund(refund(po, 1500, "OP-1"));
        assertThatThrownBy(() -> payments.requestRefund(refund(po, 600, "OP-1")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_REFUND_EXCEEDS);
        assertThat(order(po)).containsEntry("reserved_refund_minor", 1500L);
    }

    @Test
    @DisplayName("★★★ 没有商家操作员的退款 → PAY_REFUND_OPERATOR_REQUIRED：内部系统不能以系统身份退款")
    void refundWithoutOperatorRejected() {
        String po = paidOrder();
        assertThatThrownBy(() -> payments.requestRefund(refund(po, 100, " ")))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
        assertThat(order(po)).containsEntry("reserved_refund_minor", 0L);
    }

    @Test
    @DisplayName("★★★ 现有售后退款（按执行号进来）同样有退款单，业务售后单号就是 biz_refund_no；渠道失败释放占用")
    void afterSaleRefundAlsoHasRefundOrderAndFailureReleases() {
        String po = paidOrder();
        String out = (String) order(po).get("out_order_no");
        String paymentNo = jdbc.queryForObject("SELECT payment_no FROM stl_payment WHERE out_trade_no=?", String.class, out);
        String exec = "RF-AS-" + UUID.randomUUID().toString().substring(0, 8);
        executions.request(new RefundRequest(exec, "AS-" + exec, null, paymentNo, 300, "CNY", "售后"));
        assertThat(jdbc.queryForMap("SELECT biz_refund_no, out_refund_no, status FROM pay_refund WHERE out_refund_no=?",
                exec)).containsEntry("biz_refund_no", "AS-" + exec).containsEntry("status", "DISPATCHED");
        assertThat(order(po)).containsEntry("reserved_refund_minor", 300L);

        String second = "RF-AS-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("UPDATE stl_payment SET status='CLOSED' WHERE payment_no=?", paymentNo);
        var failed = executions.request(new RefundRequest(second, "AS-" + second, null, paymentNo, 100, "CNY", "售后"));
        assertThat(failed.status().name()).isEqualTo("FAILED");
        assertThat(order(po)).as("准备失败的那笔不能占着额度").containsEntry("reserved_refund_minor", 300L);
    }
}
