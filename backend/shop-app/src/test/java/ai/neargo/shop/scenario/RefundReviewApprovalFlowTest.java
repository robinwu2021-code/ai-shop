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
 * 退款两人复核切到审批服务（TDD-基础能力库对齐 §2.2 1e，Q12）：主题 {@code shop.pay_refund_review} 启用后，
 * 退款不再开 {@code pay_approval}，旧的两人复核入口拒绝；审批结果经 {@link RefundService#applyApproval} 回写，可重放。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.pay.refund.review=true",
        "shop.opskit.enabled=true", "shop.opskit.enabled-subjects=shop.pay_refund_review"})
@ActiveProfiles("test")
class RefundReviewApprovalFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private RefundService refunds;
    @Autowired private TestPayGateway bank;
    @Autowired private JdbcTemplate jdbc;

    private String paidOrder() {
        String no = "PO-RA-" + UUID.randomUUID().toString().substring(0, 8);
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
    @DisplayName("审批通过：不开 pay_approval、旧复核入口拒绝；回写放行可重放，再发起才执行")
    void approvalOutcomeReleasesTheRefund() {
        String po = paidOrder();
        String out = "RF-" + UUID.randomUUID().toString().substring(0, 8);
        var cmd = new InternalRefundCmd(PaymentPort.MALL_ORDER, po, out, "AS-" + out, 200, "七天无理由", "OP-1");
        var pending = payments.requestRefund(cmd);
        assertThat(pending.status()).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT pay_approval_id FROM pay_refund WHERE pay_refund_no=?", Long.class,
                pending.payRefundNo())).as("复核单在审批服务，支付不再开").isNull();
        assertThatThrownBy(() -> refunds.review(new PayCaller(CallerType.OPS, 1L, Set.of(), "OPS-A", null, null),
                pending.payRefundNo(), true, null))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_ORDER_NOT_FOUND);

        refunds.applyApproval(pending.payRefundNo(), true, "审批通过");
        refunds.applyApproval(pending.payRefundNo(), true, "审批通过");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE pay_refund_no=?", String.class,
                pending.payRefundNo())).isEqualTo("APPROVED");

        var dispatched = payments.requestRefund(cmd);
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        refunds.applyApproval(pending.payRefundNo(), true, "重投");
        assertThatThrownBy(() -> refunds.applyApproval(pending.payRefundNo(), false, "迟到的驳回"))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_ORDER_STATE);
    }

    @Test
    @DisplayName("审批未通过：退款单 REJECTED、执行行失败、占用释放")
    void approvalRejectionReleasesReservation() {
        String po = paidOrder();
        String out = "RF-" + UUID.randomUUID().toString().substring(0, 8);
        var pending = payments.requestRefund(new InternalRefundCmd(PaymentPort.MALL_ORDER, po, out, "AS-" + out, 300,
                "七天无理由", "OP-1"));

        refunds.applyApproval(pending.payRefundNo(), false, "金额异常");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE pay_refund_no=?", String.class,
                pending.payRefundNo())).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_payment WHERE out_trade_no=?", String.class, out))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class, po))
                .isZero();
    }
}
