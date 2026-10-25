package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC7：复核规则与两人复核（TDD-支付服务-I3支付单退款流水 S6）。{@code shop.pay.refund.review=true}。
 *
 * <p>种子只有一条 DRAFT 规则：<b>市场没有 ACTIVE 规则 = 全部复核</b>。复核期间退款不发渠道；
 * 申请人、第一复核人、第二复核人必须互不相同 —— 平台内部任何一个人都不能单方面把商户的钱退出去。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:refund-review-flow;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.pay.refund.review=true"})
@ActiveProfiles("test")
class RefundReviewFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private RefundService refunds;
    @Autowired private TestPayGateway bank;
    @Autowired private JdbcTemplate jdbc;

    private static PayCaller ops(String who) {
        return new PayCaller(CallerType.OPS, 1L, Set.of(), who, null, null);
    }

    private String paidOrder() {
        String no = "PO-RV-" + UUID.randomUUID().toString().substring(0, 8);
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
    @DisplayName("★★★ 无 ACTIVE 规则 → 待复核、不发渠道；两人复核通过后再发起才执行")
    void noActiveRuleWaitsForTwoPersonReview() {
        String po = paidOrder();
        String out = "RF-" + UUID.randomUUID().toString().substring(0, 8);
        var cmd = new InternalRefundCmd(PaymentPort.MALL_ORDER, po, out, "AS-" + out, 200, "七天无理由", "OP-1");
        var pending = payments.requestRefund(cmd);
        assertThat(pending.status()).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForMap("SELECT hit_reasons, pay_approval_id FROM pay_refund WHERE pay_refund_no=?",
                pending.payRefundNo())).containsEntry("hit_reasons", "NO_ACTIVE_RULE");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_payment WHERE out_trade_no=?", String.class, out))
                .as("复核期间退款不发渠道").isEqualTo("INIT");

        assertThatThrownBy(() -> refunds.review(ops("OP-1"), pending.payRefundNo(), true, null))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_APPROVAL_SAME_PERSON);
        refunds.review(ops("OPS-A"), pending.payRefundNo(), true, null);
        assertThatThrownBy(() -> refunds.review(ops("OPS-A"), pending.payRefundNo(), true, null))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_APPROVAL_SAME_PERSON);
        refunds.review(ops("OPS-B"), pending.payRefundNo(), true, null);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_approval WHERE id=(SELECT pay_approval_id FROM pay_refund"
                + " WHERE pay_refund_no=?)", String.class, pending.payRefundNo())).isEqualTo("APPROVED");

        var dispatched = payments.requestRefund(cmd);
        assertThat(dispatched.payRefundNo()).isEqualTo(pending.payRefundNo());
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("★★★ 复核驳回：退款单 REJECTED、执行行失败（售后侧能知道）、占用释放")
    void rejectionReleasesReservation() {
        String po = paidOrder();
        String out = "RF-" + UUID.randomUUID().toString().substring(0, 8);
        var pending = payments.requestRefund(new InternalRefundCmd(PaymentPort.MALL_ORDER, po, out, "AS-" + out, 300,
                "七天无理由", "OP-1"));
        refunds.review(ops("OPS-A"), pending.payRefundNo(), false, "金额异常");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE pay_refund_no=?", String.class,
                pending.payRefundNo())).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_payment WHERE out_trade_no=?", String.class, out))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class, po))
                .isZero();
    }
}
