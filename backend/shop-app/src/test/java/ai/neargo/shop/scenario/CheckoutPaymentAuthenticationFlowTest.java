package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.PaymentOperationService;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.CheckoutPort.InitiatePaymentReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.OrderService;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real current sessions, owner rows and durable original keys across a failed pay-owner call. */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutPaymentAuthenticationFlowTest extends CheckoutFlowFixture {
    @Autowired private CheckoutService service;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private PaymentOperationService financialOwner;
    @Autowired private OrderService orders;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private PaymentOperationPort payment;
    @MockitoSpyBean private TestPayGateway gateway;

    @Test
    void lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend() {
        assertLateSuccessfulOriginalBlocksUnsentReplay(false);
    }

    @Test
    void lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt() {
        assertLateSuccessfulOriginalBlocksUnsentReplay(true);
    }

    private void assertLateSuccessfulOriginalBlocksUnsentReplay(boolean preparedAlias) {
        var quote = placed();
        var firstRequest = new InitiatePaymentReq(quote.operation(), "rejected-a-" + sku, "TEST");
        doReturn(PayGateway.PrepayResult.notSent("isolated proved unsent"))
                .when(gateway)
                .prepay(any());
        var rejected = asBuyer(() -> checkout.initiatePayment(firstRequest));
        reset(gateway);
        assertThat(rejected.phase()).isEqualTo("REJECTED");
        assertThat(ledger.paymentByOutTradeNo(rejected.outTradeNo()).status()).isEqualTo("CLOSED");
        var request = new InitiatePaymentReq(quote.operation(), "accepted-b-" + sku, "TEST");
        var accepted = acceptBeforeFinancialDispatch(quote, request, true);
        var pendingOperation = accepted.paymentOperationNo();
        if (preparedAlias) {
            var newerRequest =
                    new InitiatePaymentReq(quote.operation(), "pending-c-" + sku, "TEST");
            pendingOperation =
                    acceptBeforeFinancialDispatch(quote, newerRequest, true).paymentOperationNo();
        }
        var pending = financialOwner.open(pendingOperation);
        assertThat(pending.phase()).isEqualTo("OPENED");
        assertThat(pending.outTradeNo()).isNotEqualTo(rejected.outTradeNo());
        // Real ledger settlement and the normal owner projection preserve the contrary original
        // money.
        String orderNo = originalOrder(quote);
        assertThat(
                        ledger.settle(
                                new SettlePort.PaymentSettled(
                                        rejected.outTradeNo(),
                                        "TEST",
                                        "late-original-" + sku,
                                        303,
                                        System.currentTimeMillis(),
                                        "CNY")))
                .isEqualTo(orderNo);
        var paid =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(),
                                                null,
                                                firstRequest.requestKey())));
        assertThat(paid.phase()).isEqualTo("SUCCEEDED");
        assertThat(paid.paymentOperationNo()).isEqualTo(rejected.paymentOperationNo());
        assertThat(paid.receiptNo()).startsWith("RC");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM stl_payment WHERE out_trade_no=?",
                                String.class,
                                pending.outTradeNo()))
                .isEqualTo("PENDING");

        assertThatThrownBy(() -> asBuyer(() -> checkout.initiatePayment(request)))
                .isInstanceOf(BizException.class)
                .satisfies(
                        error ->
                                assertThat(((BizException) error).errorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> financialOwner.claimSend(pending.operationNo()))
                .isInstanceOf(BizException.class);
        verify(gateway, never()).prepay(any());
        var original =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(original.paymentOperationNo()).isEqualTo(accepted.paymentOperationNo());
        assertThat(original.outTradeNo()).isEqualTo(pending.outTradeNo());
        assertThat(original.phase()).isEqualTo("OPENED");
        assertThat(original.receiptNo()).isNull();
        assertThat(original.payParams()).isEmpty();
        var alias = payment.query(accepted.paymentOperationNo());
        assertThat(alias.canonicalOperationNo()).isEqualTo(pending.operationNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT phase FROM stl_payment_operation WHERE operation_no=?",
                                String.class,
                                accepted.paymentOperationNo()))
                .isEqualTo(preparedAlias ? "ALIASED" : "OPENED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?"
                                        + " AND operation_no<>? AND send_started_at IS NOT NULL",
                                Long.class,
                                orderNo,
                                rejected.paymentOperationNo()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                    + " order_no=?",
                                Long.class,
                                orderNo))
                .isEqualTo(preparedAlias ? 3L : 2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?",
                                Long.class,
                                orderNo))
                .isEqualTo(preparedAlias ? 3L : 2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                Long.class,
                                orderNo))
                .isEqualTo(2L);
        assertThat(ledger.paymentByOutTradeNo(pending.outTradeNo()).status()).isEqualTo("PENDING");
        assertThat(ledger.paymentByOutTradeNo(rejected.outTradeNo()).status()).isEqualTo("SUCCESS");
        assertThat(ledger.paymentByOutTradeNo(rejected.outTradeNo()).amountMinor()).isEqualTo(303L);
        var stillPaid =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(),
                                                null,
                                                firstRequest.requestKey())));
        assertThat(stillPaid.receiptNo()).isEqualTo(paid.receiptNo());
        assertThat(stillPaid.outTradeNo()).isEqualTo(rejected.outTradeNo());
        assertThat(payment.query(rejected.paymentOperationNo()).canonicalOperationNo())
                .isEqualTo(rejected.paymentOperationNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pay_trade_no FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo(ledger.paymentByOutTradeNo(rejected.outTradeNo()).tradeNo());
    }

    @Test
    void acceptedKeyCannotFirstSendAfterActualOrderCancellation() {
        assertUnsentReplayAfterTerminal("CANCELLED", false);
    }

    @Test
    void acceptedKeyCannotFirstSendAfterActualOrderExpiry() {
        assertUnsentReplayAfterTerminal("CLOSED", false);
    }

    @Test
    void openedOriginalCannotFirstSendAfterActualOrderCancellation() {
        assertUnsentReplayAfterTerminal("CANCELLED", true);
    }

    @Test
    void acceptedKeyCannotFirstSendAfterDeadlineBeforeExpirySweep() {
        assertUnsentReplayAfterTerminal("EXPIRED", false);
    }

    @Test
    void previouslyAcceptedPaidFlagWithoutRealReceiptCannotAuthorizeFirstSend() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "false-paid-" + sku, "TEST");
        var accepted = acceptBeforeFinancialDispatch(quote, request, false);
        String orderNo = originalOrder(quote);
        jdbc.update(
                "UPDATE ord_order SET status='PAID',pay_channel='TEST',pay_trade_no=? WHERE"
                    + " order_no=?",
                "unbacked-" + sku,
                orderNo);
        assertThatThrownBy(() -> asBuyer(() -> checkout.initiatePayment(request)))
                .isInstanceOf(BizException.class);
        verify(gateway, never()).prepay(any());
        var original =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(original.paymentOperationNo()).isEqualTo(accepted.paymentOperationNo());
        assertThat(original.phase()).isEqualTo("PREPARED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                Long.class,
                                orderNo))
                .isZero();
    }

    private void assertUnsentReplayAfterTerminal(String terminal, boolean opened) {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "unsent-terminal-" + sku, "TEST");
        var accepted = acceptBeforeFinancialDispatch(quote, request, opened);
        if (opened) {
            assertThat(financialOwner.open(accepted.paymentOperationNo()).phase())
                    .isEqualTo("OPENED");
        }
        String orderNo = originalOrder(quote);
        if (!"CANCELLED".equals(terminal)) {
            jdbc.update("UPDATE ord_order SET pay_deadline_at=1 WHERE order_no=?", orderNo);
            if ("CLOSED".equals(terminal)) {
                assertThat(orders.closeExpiredOrders(2)).isPositive();
            }
        } else {
            asBuyer(() -> orders.cancel(orderNo, "cancel before first send"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("EXPIRED".equals(terminal) ? "WAIT_PAY" : terminal);
        assertThatThrownBy(() -> asBuyer(() -> checkout.initiatePayment(request)))
                .isInstanceOf(BizException.class);
        verify(gateway, never()).prepay(any());
        var same =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(same.paymentOperationNo()).isEqualTo(accepted.paymentOperationNo());
        assertThat(same.phase()).isEqualTo(opened ? "OPENED" : "PREPARED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                    + " operation_no=?",
                                Long.class,
                                quote.operation().operationNo()))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?"
                                        + " AND send_started_at IS NOT NULL",
                                Long.class,
                                orderNo))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                Long.class,
                                orderNo))
                .isEqualTo(opened ? 1L : 0L);
    }

    @Test
    void acceptedUnknownStillRecoversOriginalActualMoneyAfterCancellation() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "accepted-unknown-" + sku, "TEST");
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new IllegalStateException("isolated accepted response lost");
                        })
                .when(gateway)
                .prepay(any());
        var first = asBuyer(() -> checkout.initiatePayment(request));
        reset(gateway);
        assertThat(first.phase()).isEqualTo("UNKNOWN");
        String orderNo = originalOrder(quote);
        asBuyer(() -> orders.cancel(orderNo, "cancel after channel acceptance"));
        assertThat(gateway.markPaid(first.outTradeNo())).isTrue();
        var recovered = asBuyer(() -> checkout.initiatePayment(request));
        assertThat(recovered.phase()).isEqualTo("MANUAL_REQUIRED");
        assertThat(recovered.paymentOperationNo()).isEqualTo(first.paymentOperationNo());
        assertThat(recovered.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(recovered.receiptNo()).startsWith("RC");
        assertThat(ledger.paymentByOutTradeNo(first.outTradeNo()).status()).isEqualTo("SUCCESS");
        verify(gateway, never()).prepay(any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM ord_payment_resolution WHERE pay_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("LATE_PAYMENT");
    }

    @Test
    void paidOrderRejectsNewKeyBeforeMappingWhilePreviouslyAcceptedAliasRecoversActualReceipt() {
        var quote = placed();
        var waiting =
                new InitiatePaymentReq(quote.operation(), "accepted-before-paid-" + sku, "TEST");
        var accepted = acceptBeforeFinancialDispatch(quote, waiting, false);
        var winner = new InitiatePaymentReq(quote.operation(), "winner-" + sku, "TEST");
        var first = asBuyer(() -> checkout.initiatePayment(winner));
        assertThat(gateway.markPaid(first.outTradeNo())).isTrue();
        var paid =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, winner.requestKey())));
        assertThat(paid.phase()).isEqualTo("SUCCEEDED");
        reset(gateway);
        var alias = asBuyer(() -> checkout.initiatePayment(waiting));
        assertThat(alias.paymentOperationNo()).isEqualTo(accepted.paymentOperationNo());
        assertThat(alias.outTradeNo()).isEqualTo(paid.outTradeNo());
        assertThat(alias.receiptNo()).isEqualTo(paid.receiptNo());
        assertThat(alias.phase()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.initiatePayment(
                                                        new InitiatePaymentReq(
                                                                quote.operation(),
                                                                "after-paid-new-" + sku,
                                                                "TEST"))))
                .isInstanceOf(BizException.class);
        verify(gateway, never()).prepay(any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                    + " operation_no=?",
                                Long.class,
                                quote.operation().operationNo()))
                .isEqualTo(2L);
        String subOrderNo =
                jdbc.queryForObject(
                        "SELECT sub_order_no FROM ord_sub_order WHERE order_no=?",
                        String.class,
                        originalOrder(quote));
        var source =
                asMerchant(
                        () ->
                                checkout.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                quote.operation(), subOrderNo)));
        assertThat(source.receivedMinor()).isEqualTo(303L);
        assertThat(source.payments())
                .singleElement()
                .satisfies(actual -> assertThat(actual.receiptNo()).isEqualTo(paid.receiptNo()));
    }

    private CheckoutPort.PaymentAttemptView acceptBeforeFinancialDispatch(
            CheckoutPort.CheckoutQuote quote, InitiatePaymentReq request, boolean prepared) {
        if (prepared) {
            doThrow(new IllegalStateException("isolated dispatch unavailable"))
                    .when(payment)
                    .initiate(anyString());
        } else {
            doThrow(new IllegalStateException("isolated owner unavailable"))
                    .when(payment)
                    .prepare(any());
        }
        try {
            assertThatThrownBy(() -> asBuyer(() -> checkout.initiatePayment(request)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            reset(payment);
        }
        var accepted =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(accepted.phase()).isEqualTo("PREPARED");
        return accepted;
    }

    private String originalOrder(CheckoutPort.CheckoutQuote quote) {
        return jdbc.queryForObject(
                "SELECT order_no FROM ord_checkout_operation WHERE operation_no=?",
                String.class,
                quote.operation().operationNo());
    }

    @Test
    void definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney() {
        var quote = placed();
        var rejectedRequest = new InitiatePaymentReq(quote.operation(), "unsent-" + sku, "TEST");
        doReturn(PayGateway.PrepayResult.notSent("isolated local precondition"))
                .when(gateway)
                .prepay(any());
        var rejected = asBuyer(() -> checkout.initiatePayment(rejectedRequest));
        reset(gateway);
        assertThat(rejected.phase()).isEqualTo("REJECTED");
        assertThat(ledger.paymentByOutTradeNo(rejected.outTradeNo()).status()).isEqualTo("CLOSED");
        var replacementRequest =
                new InitiatePaymentReq(quote.operation(), "replacement-" + sku, "TEST");
        var replacement = asBuyer(() -> checkout.initiatePayment(replacementRequest));
        assertThat(replacement.outTradeNo()).isNotEqualTo(rejected.outTradeNo());
        assertThat(gateway.markPaid(replacement.outTradeNo())).isTrue();
        var paid =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(),
                                                null,
                                                replacementRequest.requestKey())));
        assertThat(paid.phase()).isEqualTo("SUCCEEDED");
        var orderNo = ledger.paymentByOutTradeNo(paid.outTradeNo()).orderNo();
        var subOrderNo =
                jdbc.queryForObject(
                        "SELECT sub_order_no FROM ord_sub_order WHERE order_no=?",
                        String.class,
                        orderNo);
        var lookup = new CheckoutPort.SourceLookup(quote.operation(), subOrderNo);
        var source = asMerchant(() -> checkout.source(scope, lookup));
        assertThat(source.receivedMinor()).isEqualTo(303L);
        assertThat(source.payments())
                .singleElement()
                .satisfies(
                        actual -> {
                            assertThat(actual.paymentOperationNo())
                                    .isEqualTo(replacement.paymentOperationNo());
                            assertThat(actual.outTradeNo()).isEqualTo(paid.outTradeNo());
                            assertThat(actual.receiptNo()).isEqualTo(paid.receiptNo());
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                    + " operation_no=?",
                                Long.class,
                                quote.operation().operationNo()))
                .isEqualTo(2L);
        // A contradictory real late receipt remains money; two different receipts cannot be folded
        // into B.
        ledger.settle(
                new SettlePort.PaymentSettled(
                        rejected.outTradeNo(),
                        "TEST",
                        "late-" + sku,
                        303,
                        System.currentTimeMillis(),
                        "CNY"));
        var contradictory = asMerchant(() -> checkout.source(scope, lookup));
        assertThat(ledger.paymentByOutTradeNo(rejected.outTradeNo()).status()).isEqualTo("SUCCESS");
        assertThat(contradictory.receivedMinor()).isNull();
        assertThat(contradictory.financialState()).isEqualTo("UNKNOWN");
        assertThat(contradictory.executionState()).isEqualTo("BLOCKED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pay_trade_no FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo(ledger.paymentByOutTradeNo(paid.outTradeNo()).tradeNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM ord_payment_resolution WHERE pay_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("DUPLICATE_PAYMENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                    + " status='SUCCESS'",
                                Long.class,
                                orderNo))
                .isEqualTo(2L);
    }

    @Test
    void originalKeySurvivesFailureBeforePayOwnerAndReadNeverDispatches() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "payment-" + sku, "TEST");
        doThrow(new IllegalStateException("isolated owner unavailable"))
                .when(payment)
                .prepare(any());
        try {
            assertThatThrownBy(() -> asBuyer(() -> checkout.initiatePayment(request)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            reset(payment);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                        + " operation_no=?",
                                Long.class,
                                quote.operation().operationNo()))
                .isEqualTo(1L);
        var lookup = new PaymentLookup(quote.operation(), null, request.requestKey());
        var accepted = asBuyer(() -> service.findPaymentForCurrentBuyer(lookup));
        assertThat(accepted.phase()).isEqualTo("PREPARED");
        assertThat(accepted.outTradeNo()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_operation WHERE operation_no=?",
                                Long.class,
                                accepted.paymentOperationNo()))
                .isZero();
        var first = asBuyer(() -> checkout.initiatePayment(request));
        assertThat(first.paymentOperationNo()).isEqualTo(accepted.paymentOperationNo());
        assertThat(first.phase()).isEqualTo("PAYABLE");
        assertThat(first.payParams()).isNotEmpty();
        jdbc.update(
                "UPDATE ord_checkout_payment_request SET created_at=DATE_SUB(NOW(),INTERVAL 2 DAY)"
                        + " WHERE payment_operation_no=?",
                first.paymentOperationNo());
        Assertions.assertThatCode(() -> asBuyer(() -> service.findPaymentForCurrentBuyer(lookup)))
                .doesNotThrowAnyException();
        var replay = asBuyer(() -> checkout.initiatePayment(request));
        assertThat(replay.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(replay.payParams()).isEqualTo(first.payParams());
        var read =
                asMerchant(
                        () ->
                                checkout.findPayment(
                                        scope,
                                        new PaymentLookup(
                                                quote.operation(),
                                                first.paymentOperationNo(),
                                                null)));
        assertThat(read.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(read.payParams()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                        + " operation_no=?",
                                Long.class,
                                quote.operation().operationNo()))
                .isEqualTo(1L);
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.initiatePayment(
                                                        new InitiatePaymentReq(
                                                                quote.operation(),
                                                                request.requestKey(),
                                                                "WECHAT"))))
                .isInstanceOf(BizException.class)
                .satisfies(
                        error ->
                                assertThat(((BizException) error).errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_PAYMENT_CONFLICT));
        assertThat(gateway.markPaid(first.outTradeNo())).isTrue();
        var paid = asBuyer(() -> service.findPaymentForCurrentBuyer(lookup));
        assertThat(paid.phase()).isEqualTo("SUCCEEDED");
        assertThat(paid.receiptNo()).startsWith("RC");
        var mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
        try {
            String callback =
                    mvc.perform(
                                    MockMvcRequestBuilders.post("/pay/callback/stub")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "outTradeNo",
                                                                    paid.outTradeNo(),
                                                                    "transactionId",
                                                                    gateway.query(paid.outTradeNo())
                                                                            .tradeNo(),
                                                                    "sign",
                                                                    "stub-secret"))))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(callback).isEqualTo("SUCCESS");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertThat(asBuyer(() -> service.findPaymentForCurrentBuyer(lookup)).receiptNo())
                .isEqualTo(paid.receiptNo());
        String orderNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM ord_checkout_payment_request"
                                + " WHERE payment_operation_no=?",
                        String.class,
                        paid.paymentOperationNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_sub_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("FULFILLING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT verify_code FROM ord_sub_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isNull();
    }

    @Test
    void merchantAndRevokedConsumerCannotInitiateOrReadParamsAndOldManagedPayCannotBypass() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "payment-" + sku, "TEST");
        assertThatThrownBy(() -> asMerchant(() -> checkout.initiatePayment(request)))
                .isInstanceOf(BizException.class);
        asBuyer(
                () -> {
                    tokens.of(Realm.CONSUMER).revoke(SecurityUtils.currentToken().orElseThrow());
                    assertThatThrownBy(() -> checkout.initiatePayment(request))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(
                                    () ->
                                            service.findPaymentForCurrentBuyer(
                                                    new PaymentLookup(
                                                            quote.operation(),
                                                            null,
                                                            request.requestKey())))
                            .isInstanceOf(BizException.class);
                    return null;
                });
        String orderNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM ord_checkout_operation WHERE operation_no=?",
                        String.class,
                        quote.operation().operationNo());
        assertThatThrownBy(() -> asBuyer(() -> orders.pay(orderNo, "TEST")))
                .isInstanceOf(BizException.class)
                .satisfies(
                        error ->
                                assertThat(((BizException) error).errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                Long.class,
                                orderNo))
                .isZero();
    }

    @Test
    void revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "payment-" + sku, "TEST");
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            tokens.of(Realm.CONSUMER)
                                    .revoke(SecurityUtils.currentToken().orElseThrow());
                            return result;
                        })
                .when(gateway)
                .prepay(any());
        try {
            assertThatThrownBy(() -> asBuyer(() -> checkout.initiatePayment(request)))
                    .isInstanceOf(BizException.class)
                    .satisfies(
                            error ->
                                    assertThat(((BizException) error).errorCode())
                                            .isEqualTo(ErrorCode.UNAUTHORIZED));
        } finally {
            reset(gateway);
        }
        var recovered =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(recovered.phase()).isEqualTo("PAYABLE");
        assertThat(recovered.payParams()).isNotEmpty();
    }

    @Test
    void realReceiptCannotProjectAcrossChangedOriginalManagedStoreFacts() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "payment-" + sku, "TEST");
        var initiated = asBuyer(() -> checkout.initiatePayment(request));
        String orderNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM ord_checkout_operation WHERE operation_no=?",
                        String.class,
                        quote.operation().operationNo());
        jdbc.update("UPDATE ord_sub_order SET store_no='FOREIGN_STORE' WHERE order_no=?", orderNo);
        assertThat(gateway.markPaid(initiated.outTradeNo())).isTrue();
        var result =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(result.phase()).isEqualTo("MANUAL_REQUIRED");
        assertThat(result.receiptNo()).startsWith("RC");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM stl_payment WHERE out_trade_no=?",
                                String.class,
                                initiated.outTradeNo()))
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("WAIT_PAY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM ord_payment_resolution WHERE pay_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("RECEIPT_MISMATCH");
    }

    @Test
    void realReceiptCannotProjectIfOriginalItemShareNoLongerMatchesConsumedQuote() {
        var quote = placed();
        var request = new InitiatePaymentReq(quote.operation(), "payment-" + sku, "TEST");
        var initiated = asBuyer(() -> checkout.initiatePayment(request));
        String orderNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM ord_checkout_operation WHERE operation_no=?",
                        String.class,
                        quote.operation().operationNo());
        jdbc.update(
                "UPDATE ord_item SET paid_share_minor=paid_share_minor+1 WHERE order_no=?",
                orderNo);
        assertThat(gateway.markPaid(initiated.outTradeNo())).isTrue();
        var observed =
                asBuyer(
                        () ->
                                service.findPaymentForCurrentBuyer(
                                        new PaymentLookup(
                                                quote.operation(), null, request.requestKey())));
        assertThat(observed.phase()).isEqualTo("MANUAL_REQUIRED");
        assertThat(observed.receiptNo()).startsWith("RC");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("WAIT_PAY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM ord_payment_resolution WHERE pay_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("RECEIPT_MISMATCH");
    }

    @Test
    void zeroDueManagedCheckoutImmediatelyProjectsItsActualFreeReceipt() {
        jdbc.update("UPDATE prd_sku SET price=0 WHERE sku_no=?", sku);
        var quote = placed();
        assertThat(quote.dueMinor()).isZero();
        var result =
                asBuyer(
                        () ->
                                checkout.initiatePayment(
                                        new InitiatePaymentReq(
                                                quote.operation(), "zero-original", "TEST")));
        assertThat(result.phase()).isEqualTo("SUCCEEDED");
        assertThat(result.channel()).isEqualTo("FREE");
        assertThat(result.receiptNo()).startsWith("RC");
        String orderNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM ord_checkout_operation WHERE operation_no=?",
                        String.class,
                        quote.operation().operationNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT verify_code FROM ord_sub_order WHERE order_no=?",
                                String.class,
                                orderNo))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                    + " amount_minor=0 AND pay_channel='FREE' AND status='SUCCESS'",
                                Long.class,
                                orderNo))
                .isEqualTo(1L);
        Mockito.verify(gateway, Mockito.never()).prepay(any());
    }

    @Test
    void simultaneousOriginalKeyAndNewAliasKeepOnePaymentAndOneLockFreeSend() throws Exception {
        var quote = placed();
        var original = new InitiatePaymentReq(quote.operation(), "concurrent-original", "TEST");
        var alias = new InitiatePaymentReq(quote.operation(), "concurrent-alias", "TEST");
        var start = new CyclicBarrier(3);
        var sends = new AtomicInteger();
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            sends.incrementAndGet();
                            return call.callRealMethod();
                        })
                .when(gateway)
                .prepay(any());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return asBuyer(() -> checkout.initiatePayment(original));
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return asBuyer(() -> checkout.initiatePayment(original));
                            });
            var c =
                    executor.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return asBuyer(() -> checkout.initiatePayment(alias));
                            });
            var first = a.get(15, TimeUnit.SECONDS);
            var same = b.get(15, TimeUnit.SECONDS);
            var other = c.get(15, TimeUnit.SECONDS);
            assertThat(same.paymentOperationNo()).isEqualTo(first.paymentOperationNo());
            assertThat(other.paymentOperationNo()).isNotEqualTo(first.paymentOperationNo());
            assertThat(same.outTradeNo()).isEqualTo(first.outTradeNo());
            assertThat(other.outTradeNo()).isEqualTo(first.outTradeNo());
            assertThat(sends.get()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                            + " operation_no=?",
                                    Long.class,
                                    quote.operation().operationNo()))
                    .isEqualTo(2L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE out_trade_no=?",
                                    Long.class,
                                    first.outTradeNo()))
                    .isEqualTo(1L);
        } finally {
            reset(gateway);
        }
    }

    @Test
    void currentConsumerHttpRejectsForeignBuyerMerchantForgedAmountAndRevokedRead()
            throws Exception {
        var quote = placed();
        var pool = tokens.of(Realm.CONSUMER);
        String current = pool.issue(new TokenStore.SessionData(LoginUser.consumer(buyer, "buyer")));
        String foreign =
                pool.issue(
                        new TokenStore.SessionData(
                                LoginUser.consumer("foreign-" + buyer, "foreign")));
        String merchant =
                tokens.of(Realm.MERCHANT)
                        .issue(new TokenStore.SessionData(LoginUser.merchant(actor, "cashier")));
        String base = "/mp/checkout/" + quote.operation().operationNo() + "/payments";
        try {
            for (String denied : List.of(foreign, merchant)) {
                assertThat(
                                paymentHttp(
                                                denied,
                                                base,
                                                "shared-raw-key",
                                                "{\"channel\":\"TEST\"}")
                                        .path("code")
                                        .asInt())
                        .isNotZero();
            }
            assertThat(
                            paymentHttp(
                                            current,
                                            base,
                                            "forged",
                                            "{\"channel\":\"TEST\",\"amountMinor\":0}")
                                    .path("code")
                                    .asInt())
                    .isEqualTo(10400);
            var first = paymentHttp(current, base, "shared-raw-key", "{\"channel\":\"TEST\"}");
            assertThat(first.path("code").asInt()).isZero();
            assertThat(first.path("data").path("payParams").size()).isPositive();
            assertThat(
                            paymentHttp(foreign, base + "/lookup", "shared-raw-key", "{}")
                                    .path("code")
                                    .asInt())
                    .isNotZero();
            pool.revoke(current);
            assertThat(
                            paymentHttp(current, base + "/lookup", "shared-raw-key", "{}")
                                    .path("code")
                                    .asInt())
                    .isNotZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                            + " operation_no=?",
                                    Long.class,
                                    quote.operation().operationNo()))
                    .isEqualTo(1L);
        } finally {
            pool.revoke(current);
            pool.revoke(foreign);
            tokens.of(Realm.MERCHANT).revoke(merchant);
        }
    }

    private JsonNode paymentHttp(String token, String path, String key, String body)
            throws Exception {
        var mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
        return json.readTree(
                mvc.perform(
                                MockMvcRequestBuilders.post(path)
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private CheckoutPort.CheckoutQuote placed() {
        String payMerchant = "QP-" + sku;
        jdbc.update("UPDATE mch_entity SET funds_mode='DIRECT' WHERE entity_no=?", entity);
        jdbc.update("UPDATE mch_store SET pay_merchant_no=? WHERE store_no=?", payMerchant, store);
        jdbc.update(
                "INSERT INTO"
                    + " mch_payment_merchant(entity_no,store_no,pay_merchant_no,pay_channel,"
                        + "apply_status,sub_mchid,created_at,updated_at)"
                    + " VALUES (?,?,?,'TEST','ACTIVE','test-owner',NOW(),NOW())",
                entity,
                store,
                payMerchant);
        var quote = asMerchant(() -> checkout.prepare(scope, request(null)));
        var confirmation = asBuyer(() -> confirm(quote));
        asBuyer(() -> checkout.place(place(quote, confirmation)));
        return quote;
    }
}
