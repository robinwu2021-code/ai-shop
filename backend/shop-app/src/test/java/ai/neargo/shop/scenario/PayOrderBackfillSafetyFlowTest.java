package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill.Status;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.service.impl.RefundPaymentStore;
import ai.neargo.shop.spi.settle.RefundRequest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Historical cash is linked only from sufficient facts, without new financial execution. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-backfill-safety;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayOrderBackfillSafetyFlowTest {
    @Autowired private PaymentLedgerService ledger;
    @Autowired private PayOrderBackfill backfill;
    @Autowired private PaymentPort payments;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private LedgerService journals;
    @Autowired private RefundPaymentStore refundStore;

    private record Fixture(String order, String payment) {}

    private Long store() {
        return jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
    }

    private Fixture legacy() {
        String order = "BF-" + UUID.randomUUID().toString().substring(0, 8);
        ledger.open(new SettlePort.PaymentOpen(order, "USER-" + order, null, "TEST", 1990, "goods"));
        ledger.settle(new SettlePort.PaymentSettled(order, "TEST", "TX-" + order, 1990,
                System.currentTimeMillis(), "CNY"));
        jdbc.update("UPDATE stl_payment SET funds_path='AGGREGATED',market='CN' WHERE order_no=?", order);
        return new Fixture(order, ledger.paymentNoOf(order).orElseThrow());
    }

    private Long apply(Fixture f, String path) {
        return backfill.backfill(f.order(), PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", path);
    }

    private void assertUntouched(Fixture f) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE out_order_no=?",
                    Long.class, f.order()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND pay_order_id IS NOT NULL",
                Long.class, f.order())).isZero();
    }

    private PayOrderBackfill.Assessment inspect(Fixture f) {
        return backfill.inspect(f.order(), PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", null);
    }

    @Test void provenRefundLinkCanBeRepairedWithoutReplayingOrChangingItsLiveBalance() {
        var f = legacy();
        Long id = apply(f, "AGGREGATED");
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "live", "OPS-ORIGINAL");
        jdbc.update("UPDATE stl_payment SET pay_order_id=NULL WHERE payment_no=?", refund);
        assertThat(inspect(f).status().name()).isEqualTo("LINKS_ONLY");
        Long vouchers = jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher", Long.class);
        var approval = jdbc.queryForMap("SELECT status,requested_by FROM pay_refund WHERE biz_refund_no=?",
                "AS-" + f.order());
        assertThat(apply(f, "AGGREGATED")).isEqualTo(id);
        assertThat(apply(f, "AGGREGATED")).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE payment_no=?", Long.class, refund))
                .isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE id=?", Long.class, id))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher", Long.class)).isEqualTo(vouchers);
        assertThat(jdbc.queryForMap("SELECT status,requested_by FROM pay_refund WHERE biz_refund_no=?",
                "AS-" + f.order())).isEqualTo(approval);
        assertThat(backfill.pendingOrderNos("", 1000)).doesNotContain(f.order());
    }

    @Test void missingRefundIntentProofStillRequiresManualAssociationReview() {
        var f = legacy();
        apply(f, "AGGREGATED");
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "live", "OPS-ORIGINAL");
        jdbc.update("UPDATE stl_payment SET pay_order_id=NULL,pay_refund_id=NULL WHERE payment_no=?", refund);
        assertThat(inspect(f).status()).isEqualTo(Status.BLOCKED);
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE payment_no=?", Long.class, refund))
                .isNull();
    }

    @Test void newCashRefundAfterBackfillKeepsItsOriginalPaymentOrderAssociation() {
        var f = legacy();
        Long id = apply(f, "AGGREGATED");
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "new", "OPS-NEW");
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE payment_no=?", Long.class, refund))
                .isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM pay_refund WHERE biz_refund_no=?", Long.class,
                "AS-" + f.order())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE id=?", Long.class, id))
                .isEqualTo(200);
        assertThat(backfill.pendingOrderNos("", 1000)).doesNotContain(f.order());
        assertThat(apply(f, "AGGREGATED")).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT reserved_refund_minor FROM pay_order WHERE id=?", Long.class, id))
                .isEqualTo(200);
    }

    @Test void unifiedRefundPreparationAlsoInheritsTheOriginalPaymentOrder() {
        var f = legacy();
        Long id = apply(f, "AGGREGATED");
        var refund = refundStore.prepare(new RefundRequest("RF-" + f.order(), "AS-" + f.order(), "SUB-" + f.order(),
                f.payment(), 200, "CNY", "new", "OPS-NEW"));
        assertThat(refund.getPayOrderId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE payment_no=?", Long.class,
                refund.getPaymentNo())).isEqualTo(id);
        assertThat(backfill.pendingOrderNos("", 1000)).doesNotContain(f.order());
    }

    @Test void previewShowsReliableAmountsWithoutAnyWrites() {
        var f = legacy();
        Long vouchers = jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher", Long.class);
        Long entries = jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_entry", Long.class);
        var facts = inspect(f);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher", Long.class)).isEqualTo(vouchers);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_entry", Long.class)).isEqualTo(entries);
        assertThat(facts.status()).isEqualTo(Status.READY);
        assertThat(facts.fundsPath()).isEqualTo("AGGREGATED");
        assertThat(facts.paidMinor()).isEqualTo(1990);
        assertThat(facts.userNo()).isEqualTo("USER-" + f.order());
        assertThat(facts.outTradeNo()).isEqualTo(f.order());
        assertUntouched(f);
    }

    @Test void originalPaymentRequestCanProveMissingFrozenPathWithoutChangingHistoricalColumns() {
        var f = legacy();
        frozenRequest(f, "AGGREGATED");
        jdbc.update("UPDATE stl_payment SET funds_path=NULL,market=NULL WHERE payment_no=?", f.payment());
        var facts = inspect(f);
        assertThat(facts.fundsPath()).isEqualTo("AGGREGATED");
        assertThat(facts.storeNos()).containsExactly("STORE");
        assertThat(facts.merchantNos()).containsExactly("MERCHANT");
        Long id = apply(f, "AGGREGATED");
        assertThat(jdbc.queryForObject("SELECT funds_path FROM pay_order WHERE id=?", String.class, id))
                .isEqualTo("AGGREGATED");
        var original = jdbc.queryForMap("SELECT market,funds_path FROM stl_payment WHERE payment_no=?", f.payment());
        assertThat(original).containsEntry("market", null).containsEntry("funds_path", null);
    }

    @Test void contradictoryFrozenRoutesAreNotReconciledByGuessing() {
        var f = legacy();
        frozenRequest(f, "DIRECT");
        assertThat(inspect(f).reason()).isEqualTo("FUNDS_PATH_CONFLICT");
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).hasMessage("PAY_BACKFILL_FUNDS_PATH_CONFLICT");
        assertUntouched(f);
    }

    private void frozenRequest(Fixture f, String path) {
        var route = new PaymentRoute("MERCHANT", "STORE", "PM", "TEST", "SM", "PM:1", path);
        String snapshot = new ObjectMapper().writeValueAsString(new SettlePort.PaymentOpen(f.order(),
                "USER-" + f.order(), null, "TEST", 1990, "goods", "CNY",
                List.of(new SettlePort.PaymentStore("SUB-" + f.order(), route))));
        jdbc.update("UPDATE stl_payment SET payment_request_json=? WHERE payment_no=?", snapshot, f.payment());
    }

    @Test void refundCannotBeTransferredFromAnotherOriginalCashInstruction() {
        var f = legacy();
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "historic");
        ledger.markRefundSettled(refund, "FINAL-" + f.order(), 200);
        jdbc.update("UPDATE stl_payment SET origin_payment_no='ANOTHER-PAYMENT' WHERE payment_no=?", refund);
        assertThat(inspect(f).reason()).isEqualTo("REFUND_ORIGIN_REQUIRED");
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).hasMessage("PAY_BACKFILL_REFUND_ORIGIN_REQUIRED");
        assertUntouched(f);
    }

    @Test void callerCannotSupplyMissingHistoricalFundsEvidence() {
        var f = legacy();
        jdbc.update("UPDATE stl_payment SET funds_path=NULL WHERE order_no=?", f.order());
        assertThat(inspect(f).reason()).isEqualTo("FROZEN_FUNDS_PATH_REQUIRED");
        assertThatThrownBy(() -> apply(f, "AGGREGATED"))
                .hasMessage("PAY_BACKFILL_FROZEN_FUNDS_PATH_REQUIRED");
        assertUntouched(f);
    }

    @Test void successfulAttemptSuppliesChannelWithoutInventingOlderAttemptRouting() {
        String order = "BF-" + UUID.randomUUID().toString().substring(0, 8);
        ledger.open(new SettlePort.PaymentOpen(order, "USER-" + order, null, "WECHAT", 1990, "old"));
        ledger.close(order, "old attempt closed");
        String actual = ledger.open(new SettlePort.PaymentOpen(order, "USER-" + order, null, "TEST", 1990, "paid"));
        ledger.settle(new SettlePort.PaymentSettled(actual, "TEST", "TX-" + actual, 1990,
                System.currentTimeMillis(), "CNY"));
        jdbc.update("UPDATE stl_payment SET funds_path='AGGREGATED',market='CN' WHERE out_trade_no=?", actual);
        var f = new Fixture(order, ledger.paymentNoOf(actual).orElseThrow());
        Long id = apply(f, "AGGREGATED");
        assertThat(jdbc.queryForObject("SELECT pay_channel FROM pay_order WHERE id=?", String.class, id))
                .isEqualTo("TEST");
        assertThat(jdbc.queryForObject("SELECT funds_path FROM stl_payment WHERE out_trade_no=?", String.class, order))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND pay_order_id=?",
                Long.class, order, id)).isEqualTo(2);
    }

    @Test void differentTenantAttemptCannotJoinThePaymentOrder() {
        var f = legacy();
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,user_no,amount_minor,currency,status,"
                + "pay_channel,out_trade_no,tenant_no,created_at,updated_at)"
                + " VALUES(?,'PAY',?,?,1990,'CNY','FAILED','TEST',?,'FOREIGN',NOW(),NOW())",
                "FOREIGN-" + f.order(), f.order(), "USER-" + f.order(), "FOREIGN-OUT-" + f.order());
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).isInstanceOf(IllegalStateException.class);
        assertUntouched(f);
    }

    @Test void requestSnapshotConflictBlocksWithoutRepairingTheOriginalRecord() {
        var f = legacy();
        String conflicting = new ObjectMapper().writeValueAsString(new SettlePort.PaymentOpen(f.order(),
                "USER-" + f.order(), null, "TEST", 1991, "goods"));
        jdbc.update("UPDATE stl_payment SET payment_request_json=? WHERE payment_no=?", conflicting, f.payment());
        assertThat(inspect(f).reason()).isEqualTo("PAYMENT_SNAPSHOT_CONFLICT");
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).hasMessage("PAY_BACKFILL_PAYMENT_SNAPSHOT_CONFLICT");
        assertUntouched(f);
    }

    @Test void definitiveRefundFailureStillNeedsSourceCompensationBeforeBackfill() {
        var f = legacy();
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "failed");
        ledger.markRefundRejected(refund, "channel rejected");
        assertThat(inspect(f).reason()).isEqualTo("REFUND_UNRESOLVED");
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).hasMessage("PAY_BACKFILL_REFUND_UNRESOLVED");
        assertUntouched(f);
    }

    @Test void openingFailureRollsBackOrderLinksAndTheEarlierPaymentVoucher() {
        var f = legacy();
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "historic");
        ledger.markRefundSettled(refund, "FINAL-" + f.order(), 200);
        doThrow(new IllegalStateException("refund journal unavailable")).when(journals)
                .post(argThat(v -> "REFUND_SUCCEEDED".equals(v.voucherType())));
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).hasMessage("refund journal unavailable");
        assertUntouched(f);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher WHERE memo='B7 流水期初回放'"
                + " AND ref_id NOT IN (SELECT id FROM pay_order)", Long.class)).isZero();
    }

    @Test void simultaneousBackfillsCreateOneOrderAndOneOpening() throws Exception {
        var f = legacy();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> { start.await(); return apply(f, "AGGREGATED"); });
            var two = workers.submit(() -> { start.await(); return apply(f, "AGGREGATED"); });
            start.countDown();
            Long id = one.get(15, TimeUnit.SECONDS);
            assertThat(two.get(15, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE out_order_no=?",
                    Long.class, f.order()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher WHERE idem_key=?", Long.class,
                    "PAY_SUCCEEDED|BACKFILL|" + id)).isEqualTo(1);
        }
    }

    @Test void candidateCursorAdvancesWithoutRemovingBlockedRecords() {
        var f = legacy();
        jdbc.update("UPDATE stl_payment SET funds_path=NULL WHERE order_no=?", f.order());
        assertThat(backfill.pendingOrderNos("", 1000)).contains(f.order());
        List<String> later = backfill.pendingOrderNos(f.order(), 1000);
        assertThat(later).allSatisfy(value -> assertThat(value).isGreaterThan(f.order()));
        assertThat(backfill.pendingOrderNos("", 1000)).contains(f.order());
    }

    @Test void currentFundsConfigurationCannotOverrideOriginalFrozenPath() {
        var f = legacy();
        assertThatThrownBy(() -> apply(f, "DIRECT")).isInstanceOf(IllegalStateException.class);
        assertUntouched(f);
    }

    @Test void duplicateSuccessfulPaymentsRequireManualResolution() {
        var f = legacy();
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,user_no,amount_minor,currency,status,"
                + "pay_channel,out_trade_no,trade_no,funds_path,market,succeeded_at,created_at,updated_at)"
                + " VALUES(?,'PAY',?,?,1990,'CNY','SUCCESS','TEST',?,?,'AGGREGATED','CN',1,NOW(),NOW())",
                "DUP-" + f.order(), f.order(), "USER-" + f.order(), "DUP-OUT-" + f.order(), "DUP-TX-" + f.order());
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).isInstanceOf(IllegalStateException.class);
        assertUntouched(f);
    }

    @Test void unresolvedRefundCannotBecomeAnUnreservedPaymentOrder() {
        var f = legacy();
        assertThat(inspect(f).status()).isEqualTo(Status.READY);
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), 200, "pending");
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).isInstanceOf(IllegalStateException.class);
        assertUntouched(f);
        assertThat(ledger.refundEvidence(refund).status()).isEqualTo("PENDING");
    }

    @Test void anotherInternalBusinessOwnerCannotReceiveADuplicatePaymentOrderForTheSameCash() {
        var f = legacy();
        payments.createPayment(new PaymentPort.InternalCreatePaymentCmd(PaymentPort.FOOD_CHECK, f.order(),
                f.order(), store(), "门店", "主体", "CN", 1990, "CNY", "goods", System.currentTimeMillis() + 90_000,
                "AGGREGATED"));
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE out_order_no=?", Long.class, f.order()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE payment_no=?",
                Long.class, f.payment()))
                .isNull();
    }

    @Test void alreadyExistingLivePaymentOrderCannotReceiveAnotherOpeningVoucher() {
        var f = legacy();
        var live = payments.createPayment(new PaymentPort.InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, f.order(),
                f.order(), store(), "门店", "主体", "CN", 1990, "CNY", "goods", System.currentTimeMillis() + 90_000,
                "AGGREGATED"));
        assertThatThrownBy(() -> apply(f, "AGGREGATED")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE pay_order_no=?", String.class,
                live.payOrderNo())).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE payment_no=?",
                Long.class, f.payment()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_entry WHERE pay_order_id=(SELECT id"
                + " FROM pay_order WHERE pay_order_no=?)", Long.class, live.payOrderNo())).isZero();
    }

    @ParameterizedTest
    @CsvSource({"200,PARTIALLY_REFUNDED", "1990,REFUNDED"})
    void openingRefundUsesPaymentOrderReferenceAndPreservesOriginalCashEvidence(long amount, String expectedState) {
        var f = legacy();
        String refund = ledger.refund(f.order(), "SUB-" + f.order(), "AS-" + f.order(), amount, "historic");
        ledger.markRefundSettled(refund, "FINAL-" + f.order(), amount);
        var evidence = ledger.refundEvidence(refund);
        String originalOut = jdbc.queryForObject("SELECT out_trade_no FROM stl_payment WHERE payment_no=?",
                String.class, refund);
        Long id = apply(f, "AGGREGATED");
        assertThat(apply(f, "AGGREGATED")).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT paid_minor,refunded_minor,reserved_refund_minor,status FROM pay_order"
                + " WHERE id=?", id)).containsEntry("paid_minor", 1990L).containsEntry("refunded_minor", amount)
                .containsEntry("reserved_refund_minor", 0L).containsEntry("status", expectedState);
        assertThat(jdbc.queryForMap("SELECT ref_type,ref_id FROM pay_ledger_voucher WHERE idem_key=?",
                "REFUND_SUCCEEDED|BACKFILL|" + id)).containsEntry("ref_type", "PAY_ORDER").containsEntry("ref_id", id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund WHERE biz_refund_no=?", Long.class,
                "AS-" + f.order())).isZero();
        assertThat(ledger.refundEvidence(refund)).isEqualTo(evidence);
        assertThat(jdbc.queryForObject("SELECT out_trade_no FROM stl_payment WHERE payment_no=?", String.class, refund))
                .isEqualTo(originalOut);
    }
}
