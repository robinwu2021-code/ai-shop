package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** E07: legacy cash intent joins refund governance without replacing the original instruction. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.pay.refund.review=true"})
@ActiveProfiles("test")
class LegacyRefundGovernanceFlowTest {
    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettleService settle;
    @Autowired private RefundService refunds;
    @Autowired private RefundExecutionService executor;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private TestPayGateway bank;

    private record Fixture(String payOrder, String order, String payment, String out) {}

    private Fixture prepared() {
        String no = "LG-" + UUID.randomUUID().toString().substring(0, 8);
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        var handle = payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store,
                "门店", "主体", "CN", 1990, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", 1990, "goods"));
        String trade = bank.placeOrder(no, 1990);
        bank.markPaid(no);
        ledger.settle(new SettlePort.PaymentSettled(no, "TEST", trade, 1990, System.currentTimeMillis(), "CNY"));
        String payment = ledger.refund(no, "SUB-" + no, "AS-" + no, 200, "legacy reason");
        return new Fixture(handle.payOrderNo(), no, payment, no + "-R1");
    }

    private String refundNo(Fixture f) {
        return jdbc.queryForObject("SELECT pay_refund_no FROM pay_refund WHERE out_refund_no=?", String.class, f.out());
    }

    private static PayCaller ops(String who) {
        return new PayCaller(CallerType.OPS, 1L, Set.of(), who, null, null);
    }

    private void approve(Fixture f) {
        refunds.review(ops("OPS-LA"), refundNo(f), true, null);
        refunds.review(ops("OPS-LB"), refundNo(f), true, null);
    }

    @Test
    void legacyIntentRequiresReviewBeforeClaimAndSendsOriginalInstructionOnce() {
        var f = prepared();
        assertThat(refundNo(f)).isNotBlank();
        assertThat(ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "replayed reason"))
                .isEqualTo(f.payment());
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE out_refund_no=?", String.class, f.out()))
                .isEqualTo("PENDING_REVIEW");
        assertThat(ledger.claimRefundDispatch(f.payment())).isFalse();
        settle.dispatchRefund(f.payment());
        verify(bank, never()).refund(any(), anyLong(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT refund_dispatch_at FROM stl_payment WHERE payment_no=?", Long.class,
                f.payment())).isNull();
        approve(f);
        settle.dispatchRefund(f.payment());
        settle.dispatchRefund(f.payment());
        verify(bank, times(1)).refund(any(), anyLong(), eq(f.out()),
                eq("legacy reason"));
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class,
                f.payOrder())).isEqualTo(200);
    }

    @Test
    void commonCallbackKeepsCashEvidenceAndProjectsSuccessOnce() {
        var f = prepared();
        approve(f);
        settle.dispatchRefund(f.payment());
        var receipt = bank.queryRefund(f.out());
        assertThat(executor.confirm(f.out(), "TEST", 200, "USD", receipt.tradeNo()).status().name())
                .isEqualTo("FAILED");
        // Acceptance and final settlement may use different provider references on the legacy source protocol.
        String finalProvider = receipt.tradeNo() + "-FINAL";
        assertThat(executor.confirm(f.out(), "TEST", 200, "CNY", finalProvider).status().name()).isEqualTo("SUCCEEDED");
        executor.confirm(f.out(), "TEST", 200, "CNY", finalProvider);
        assertThat(executor.confirm(f.out(), "TEST", 200, "CNY", "CONFLICTING-REPLAY").status().name())
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT trade_no FROM stl_payment WHERE payment_no=?", String.class,
                f.payment())).isEqualTo(receipt.tradeNo());
        assertThat(jdbc.queryForObject("SELECT refund_verified_provider_no FROM stl_payment WHERE payment_no=?",
                String.class, f.payment())).isEqualTo(finalProvider);
        assertThat(ledger.refundEvidence(f.payment()).verifiedMinor()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE out_refund_no=?", String.class, f.out()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT refunded_minor FROM pay_order WHERE pay_order_no=?", Long.class,
                f.payOrder())).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class,
                f.payOrder())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher WHERE voucher_type='REFUND_SUCCEEDED'"
                + " AND ref_type='PAY_REFUND' AND ref_id=(SELECT id FROM pay_refund WHERE out_refund_no=?)", Long.class, f.out()))
                .isEqualTo(1);
    }

    @Test
    void reviewRejectionNeverSendsAndRetainsCashCapacityForSourceCompensation() {
        var f = prepared();
        refunds.review(ops("OPS-LA"), refundNo(f), false, "拒绝");
        settle.dispatchRefund(f.payment());
        verify(bank, never()).refund(any(), anyLong(), anyString(), anyString());
        assertThat(jdbc.queryForMap("SELECT status, refund_capacity_state FROM stl_payment WHERE payment_no=?",
                f.payment())).containsEntry("status", "FAILED").containsEntry("refund_capacity_state", "RESERVED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class,
                f.payOrder())).isZero();
    }

    @Test
    void lostResponseRecoversOriginalCashIntentAndUnifiedRefund() {
        var f = prepared();
        approve(f);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost response"); })
                .when(bank).refund(any(), anyLong(), anyString(), anyString());
        settle.dispatchRefund(f.payment());
        settle.dispatchRefund(f.payment());
        settle.refreshRefund(f.payment());
        verify(bank, times(1)).refund(any(), anyLong(), anyString(), anyString());
        assertThat(ledger.refundEvidence(f.payment()).verifiedMinor()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE out_refund_no=?", String.class, f.out()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE after_sale_no=?", Long.class,
                "AS-" + f.order())).isEqualTo(1);
    }

    @Test
    void terminalQueryProjectsFailureWithoutReleasingCashCapacity() {
        var f = prepared();
        approve(f);
        settle.dispatchRefund(f.payment());
        doReturn(new RefundQueryResult(RefundQueryResult.Status.TERMINAL_FAILED, "CLOSED", f.out(),
                200, "BANK-CLOSED", "CNY", System.currentTimeMillis())).when(bank).queryRefundOutcome(f.out());
        settle.refreshRefund(f.payment());
        assertThat(jdbc.queryForObject("SELECT status FROM pay_refund WHERE out_refund_no=?", String.class, f.out()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT refund_capacity_state FROM stl_payment WHERE payment_no=?", String.class,
                f.payment())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class,
                f.payOrder())).isZero();
    }

    @Test
    void historicalIntentWithoutExecutionIdentityIsQueryOnly() {
        var f = prepared();
        approve(f);
        jdbc.update("UPDATE stl_payment SET refund_execution_no=NULL, refund_request_json=NULL WHERE payment_no=?",
                f.payment());
        settle.dispatchRefund(f.payment());
        assertThat(ledger.claimRefundDispatch(f.payment())).isFalse();
        verify(bank, never()).refund(any(), anyLong(), anyString(), anyString());
        doReturn(new RefundQueryResult(RefundQueryResult.Status.SUCCEEDED, "SUCCESS", f.out(),
                200, "OLD-RECEIPT", "CNY", System.currentTimeMillis())).when(bank).queryRefundOutcome(f.out());
        settle.refreshRefund(f.payment());
        assertThat(ledger.refundEvidence(f.payment()).verifiedMinor()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT refund_dispatch_at FROM stl_payment WHERE payment_no=?", Long.class,
                f.payment())).isNull();
    }

    @Test
    void legacyReservationAndPublicRefundShareAvailableBalance() {
        var f = prepared();
        assertThatThrownBy(() -> payments.requestRefund(new PaymentPort.InternalRefundCmd(PaymentPort.MALL_ORDER,
                f.payOrder(), "MIX-" + f.order(), "MIX-AS-" + f.order(), 1800, "mixed", "OP-MIX")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_REFUND_EXCEEDS);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE pay_order_no=?", Long.class,
                f.payOrder())).isEqualTo(200);
    }
}
