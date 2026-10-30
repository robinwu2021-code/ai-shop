package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.OrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Actual embedded beauty + member/trade/payment owners and real C session; only response loss is
 * injected.
 */
@Import(BeautySourceCheckoutIT.TestClockSelection.class)
@TestPropertySource(
        properties = {
            "shop.beauty.enabled=true",
            "shop.beauty.deployment=embedded",
            "shop.beauty.migration.mode=migrate",
            "shop.beauty.publication.archive-only=true"
        })
@EnabledIfSystemProperty(named = "beauty.source.test.native", matches = "true")
class BeautySourceCheckoutIT extends CheckoutFlowFixture {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired RefundLedgerMapper refunds;
    @Autowired PlatformTransactionManager manager;
    private MockMvc mvc;
    @Autowired TestPayGateway gateway;
    @Autowired PaymentLedgerService ledger;
    @Autowired CheckoutService checkoutService;
    @Autowired OrderService orderService;
    @MockitoSpyBean CheckoutPort checkoutSpy;
    private final AtomicInteger prepareCalls = new AtomicInteger();
    private final AtomicBoolean dropPrepare = new AtomicBoolean();

    @TestConfiguration
    static class TestClockSelection {
        @Bean
        static BeanFactoryPostProcessor primaryCheckoutClock() {
            return factory -> factory.getBeanDefinition("checkoutClock").setPrimary(true);
        }
    }

    @BeforeEach
    void sourceFixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
        reset(checkoutSpy);
        prepareCalls.set(0);
        dropPrepare.set(false);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            prepareCalls.incrementAndGet();
                            Object result = invocation.callRealMethod();
                            if (dropPrepare.compareAndSet(true, false)) {
                                throw new UncheckedIOException(
                                        new IOException(
                                                "response dropped after actual owner commit"));
                            }
                            return result;
                        })
                .when(checkoutSpy)
                .prepare(any(), any());
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return invocation.callRealMethod();
                        })
                .when(checkoutSpy)
                .source(any(), any());
        Object duration = dto("PublishServiceDefinitionVersionReq$Duration", 30, 0, 0);
        Object requirement =
                dto(
                        "PublishServiceDefinitionVersionReq$Requirement",
                        "MAIN",
                        "STAFF",
                        goods,
                        1,
                        0,
                        0,
                        30,
                        "ANY_QUALIFIED");
        asMerchant(
                () ->
                        call(
                                bean("ServiceDefinitionService"),
                                "publishDefinitionVersion",
                                entity,
                                store,
                                actor,
                                dto(
                                        "PublishServiceDefinitionVersionReq",
                                        sku,
                                        0L,
                                        duration,
                                        List.of(requirement)),
                                "definition-" + sku));
    }

    @Test
    void realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill() throws Exception {
        Object guests = bean("GuestRegistrationService");
        Object registered =
                asMerchant(
                        () ->
                                call(
                                        guests,
                                        "registerGuest",
                                        entity,
                                        store,
                                        actor,
                                        dto(
                                                "RegisterGuestReq",
                                                "host-contact-" + sku,
                                                "guest",
                                                true),
                                        "register-" + sku));
        String guest = (String) value(registered, "guestNo");
        String token = (String) value(registered, "claimToken");
        Object root =
                asMerchant(
                        () ->
                                call(
                                        bean("WorkOrderService"),
                                        "openWorkOrder",
                                        entity,
                                        store,
                                        actor,
                                        dto(
                                                "OpenWorkOrderReq",
                                                null,
                                                store,
                                                List.of(dto("AppointmentItemReq", sku, 1)),
                                                null,
                                                guest,
                                                null,
                                                List.of(),
                                                null),
                                        "open-" + sku));
        String number = (String) value(root, "workOrderNo");
        Object request = submission(root);
        assertThatThrownBy(() -> asMerchant(() -> submit(number, request)))
                .isInstanceOf(BizException.class);
        assertThat(prepareCalls).hasValue(0);
        Object claimed =
                asMerchant(
                        () ->
                                call(
                                        guests,
                                        "claimGuest",
                                        entity,
                                        store,
                                        actor,
                                        guest,
                                        dto("ClaimGuestReq", reference, token),
                                        "claim-" + sku));
        assertThat(value(claimed, "userNo")).isEqualTo(buyer);
        Object submitted = asMerchant(() -> submit(number, request));
        var quote = (CheckoutPort.CheckoutQuote) value(submitted, "quote");
        assertThat(quote.buyerNo()).isEqualTo(buyer);
        assertThatThrownBy(() -> asMerchant(() -> confirm(quote))).isInstanceOf(BizException.class);
        String consumer =
                tokens.of(Realm.CONSUMER)
                        .issue(new TokenStore.SessionData(LoginUser.consumer(buyer, "buyer")));
        JsonNode placed;
        JsonNode payment;
        try {
            placed = purchaseHttp(consumer, quote);
            Object before =
                    asMerchant(
                            () ->
                                    call(
                                            bean("WorkOrderBillService"),
                                            "getBill",
                                            entity,
                                            store,
                                            actor,
                                            number));
            assertThat(value(before, "complete")).isEqualTo(false);
            String path = "/mp/checkout/" + quote.operation().operationNo() + "/payments";
            String paymentKey = "payment-" + sku;
            assertThatThrownBy(
                            () ->
                                    asMerchant(
                                            () ->
                                                    checkout.initiatePayment(
                                                            new CheckoutPort.InitiatePaymentReq(
                                                                    quote.operation(),
                                                                    paymentKey,
                                                                    "TEST"))))
                    .isInstanceOf(BizException.class);
            var initiated = postHttp(consumer, path, paymentKey, Map.of("channel", "TEST"));
            assertThat(gateway.markPaid(initiated.path("outTradeNo").asString())).isTrue();
            payment = postHttp(consumer, path + "/lookup", paymentKey, Map.of());
            assertThat(payment.path("phase").asString()).isEqualTo("SUCCEEDED");
        } finally {
            tokens.of(Realm.CONSUMER).revoke(consumer);
        }
        Object bound =
                asMerchant(
                        () ->
                                call(
                                        bean("ServiceSubmissionService"),
                                        "recoverPending",
                                        entity,
                                        store,
                                        actor,
                                        value(submitted, "submissionNo")));
        assertThat(value(bound, "status")).isEqualTo("BOUND");
        Object bill =
                asMerchant(
                        () ->
                                call(
                                        bean("WorkOrderService"),
                                        "getBill",
                                        entity,
                                        store,
                                        actor,
                                        number));
        Object source = ((List<?>) value(bill, "sources")).getFirst();
        assertThat(value(source, "receivedMinor")).isEqualTo(101L);
        assertThat(value(source, "netReceivedMinor")).isEqualTo(101L);
        Object receipt = ((List<?>) value(source, "receipts")).getFirst();
        assertThat(value(receipt, "receiptNo")).isEqualTo(payment.path("receiptNo").asString());
        assertThat(value(receipt, "paymentOperationNo"))
                .isEqualTo(payment.path("paymentOperationNo").asString());
        assertThat(value(receipt, "outTradeNo")).isEqualTo(payment.path("outTradeNo").asString());
        assertThat(value(value(source, "observation"), "canStart")).isEqualTo(false);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                Integer.class,
                                placed.path("orderNo").asString()))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=?",
                                Integer.class,
                                entity))
                .isOne();
        assertThat(prepareCalls).hasValue(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT frozen_command FROM bty_service_submission WHERE"
                                        + " entity_no=? AND action='SUBMIT_SOURCE'",
                                String.class,
                                entity))
                .doesNotContain(reference, token);
    }

    @Test
    void lostQuoteResponseRecoversRealOriginalUnderCurrentManager() {
        Object root = openUser();
        dropPrepare.set(true);
        Object submitted =
                asMerchant(() -> submit((String) value(root, "workOrderNo"), submission(root)));
        assertThat(value(submitted, "phase")).isEqualTo("PREPARE_UNKNOWN");
        String manager = "QB-" + sku;
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                    + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                manager,
                manager);
        jdbc.update("UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?", actor);
        Object recovered =
                asManager(
                        manager,
                        () ->
                                call(
                                        bean("ServiceSubmissionService"),
                                        "recoverPending",
                                        entity,
                                        store,
                                        manager,
                                        value(submitted, "submissionNo")));
        assertThat(value(recovered, "checkoutOperationNo"))
                .isEqualTo(value(submitted, "checkoutOperationNo"));
        assertThat(value(recovered, "quote")).isInstanceOf(CheckoutPort.CheckoutQuote.class);
        assertThat(prepareCalls).hasValue(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_operation WHERE entity_no=?",
                                Integer.class,
                                entity))
                .isOne();
    }

    @Test
    void closedSourceRetainsActualLateReceiptWithoutBindingOrExecution() {
        Object root = openUser();
        String number = (String) value(root, "workOrderNo");
        Object submitted = asMerchant(() -> submit(number, submission(root)));
        var quote = (CheckoutPort.CheckoutQuote) value(submitted, "quote");
        var consent = asBuyer(() -> confirm(quote));
        var placed = asBuyer(() -> checkout.place(place(quote, consent)));
        var request =
                new CheckoutPort.InitiatePaymentReq(
                        quote.operation(), "late-payment-" + sku, "TEST");
        var initiated = asBuyer(() -> checkout.initiatePayment(request));
        asBuyer(() -> orderService.cancel(placed.orderNo(), "close before late real money"));
        ledger.settle(
                new SettlePort.PaymentSettled(
                        initiated.outTradeNo(),
                        "TEST",
                        "late-trade-" + sku,
                        101,
                        System.currentTimeMillis(),
                        "CNY"));
        asBuyer(
                () ->
                        checkoutService.findPaymentForCurrentBuyer(
                                new CheckoutPort.PaymentLookup(
                                        quote.operation(), null, request.requestKey())));
        Object bill =
                asMerchant(
                        () ->
                                call(
                                        bean("WorkOrderBillService"),
                                        "getBill",
                                        entity,
                                        store,
                                        actor,
                                        number));
        Object source = ((List<?>) value(bill, "sources")).getFirst();
        assertThat(value(source, "receivedMinor")).isEqualTo(101L);
        assertThat(((List<?>) value(source, "receipts"))).hasSize(1);
        assertThat(value(value(source, "observation"), "state")).isEqualTo("BLOCKED");
        assertThat(value(value(source, "observation"), "canStart")).isEqualTo(false);
        assertThat(value(bill, "complete")).isEqualTo(false);
        Object pending =
                asMerchant(
                        () ->
                                call(
                                        bean("ServiceSubmissionService"),
                                        "recoverPending",
                                        entity,
                                        store,
                                        actor,
                                        value(submitted, "submissionNo")));
        assertThat(value(pending, "status")).isEqualTo("SOURCE_PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=?",
                                Integer.class,
                                entity))
                .isZero();
    }

    @Test
    void realOfflineCanonicalReceiptAndRefundBillRemainOwnerFacts() throws Exception {
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", store);
        jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no=?",
                goods);
        jdbc.update(
                "INSERT INTO"
                    + " mch_qualification(qual_no,entity_no,qual_type,qual_name,status,created_at,updated_at)"
                    + " VALUES (?,?,'BUSINESS_LICENSE','license','VALID',NOW(),NOW())",
                "QL-" + sku,
                entity);
        Object root = openUser();
        String number = (String) value(root, "workOrderNo");
        Object request = submission(root, "OFFLINE");
        Object submitted = asMerchant(() -> submit(number, request));
        var quote = (CheckoutPort.CheckoutQuote) value(submitted, "quote");
        String consumer =
                tokens.of(Realm.CONSUMER)
                        .issue(new TokenStore.SessionData(LoginUser.consumer(buyer, "buyer")));
        String cashier =
                tokens.of(Realm.MERCHANT)
                        .issue(new TokenStore.SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            var placed = purchaseHttp(consumer, quote);
            String path = "/biz/checkout/" + quote.operation().operationNo() + "/offline-receipts";
            var declaration =
                    Map.of(
                            "method",
                            "CASH",
                            "amountMinor",
                            101L,
                            "currency",
                            "CNY",
                            "receivedAt",
                            Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                            "note",
                            "actual current cashier receipt");
            var cash = postHttp(cashier, path, "cash-" + sku, declaration);
            var alias = postHttp(cashier, path, "cash-alias-" + sku, declaration);
            assertThat(alias.path("receiptNo").asString())
                    .isEqualTo(cash.path("receiptNo").asString());
            assertThat(alias.path("paymentOperationNo").asString())
                    .isNotEqualTo(cash.path("paymentOperationNo").asString());
            Object bound =
                    asMerchant(
                            () ->
                                    call(
                                            bean("ServiceSubmissionService"),
                                            "recoverPending",
                                            entity,
                                            store,
                                            actor,
                                            value(submitted, "submissionNo")));
            assertThat(value(bound, "status")).isEqualTo("BOUND");
            Object bill =
                    asMerchant(
                            () ->
                                    call(
                                            bean("WorkOrderBillService"),
                                            "getBill",
                                            entity,
                                            store,
                                            actor,
                                            number));
            Object source = ((List<?>) value(bill, "sources")).getFirst();
            assertThat(value(source, "receivedMinor")).isEqualTo(101L);
            assertThat(value(source, "netReceivedMinor")).isEqualTo(101L);
            assertThat(value(source, "refundedMinor")).isEqualTo(0L);
            Object receipt = ((List<?>) value(source, "receipts")).getFirst();
            assertThat(((List<?>) value(source, "receipts"))).hasSize(1);
            assertThat(value(receipt, "receiptNo")).isEqualTo(cash.path("receiptNo").asString());
            assertThat(value(receipt, "paymentOperationNo"))
                    .isEqualTo(cash.path("paymentOperationNo").asString());
            assertThat(value(receipt, "outTradeNo")).isEqualTo(cash.path("outTradeNo").asString());
            String sub = placed.path("subOrderNo").asString();
            Long item =
                    jdbc.queryForObject(
                            "SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, sub);
            // Controlled authoritative refund facts; this is not a full refund API workflow.
            new TransactionTemplate(manager)
                    .executeWithoutResult(
                            status -> {
                                assertThat(refunds.addItemRefund(item, 1, 101)).isOne();
                                assertThat(refunds.addSubRefund(sub, 101)).isOne();
                            });
            Object refundedBill =
                    asMerchant(
                            () ->
                                    call(
                                            bean("WorkOrderBillService"),
                                            "getBill",
                                            entity,
                                            store,
                                            actor,
                                            number));
            Object refunded = ((List<?>) value(refundedBill, "sources")).getFirst();
            assertThat(value(refunded, "receivedMinor")).isEqualTo(101L);
            assertThat(value(refunded, "netReceivedMinor")).isEqualTo(0L);
            assertThat(value(refunded, "refundedMinor")).isEqualTo(101L);
            assertThat(((List<?>) value(refunded, "receipts"))).hasSize(1);
            assertThat(value(value(refunded, "observation"), "canStart")).isEqualTo(false);
            // Controlled inconsistent financial fact exercises the actual owner's UNKNOWN
            // projection.
            jdbc.update(
                    "UPDATE stl_payment SET status='FAILED' WHERE payment_no=?",
                    cash.path("paymentNo").asString());
            Object unknownBill =
                    asMerchant(
                            () ->
                                    call(
                                            bean("WorkOrderBillService"),
                                            "getBill",
                                            entity,
                                            store,
                                            actor,
                                            number));
            Object unknown = ((List<?>) value(unknownBill, "sources")).getFirst();
            assertThat(value(unknown, "receivedMinor")).isNull();
            assertThat(value(unknown, "dueMinor")).isNull();
            assertThat(value(value(unknown, "observation"), "state")).isEqualTo("UNKNOWN");
            assertThat(value(value(unknown, "observation"), "lastVerifiedRevision"))
                    .isEqualTo(value(value(refunded, "observation"), "lastVerifiedRevision"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=?",
                                    Integer.class,
                                    entity))
                    .isOne();
        } finally {
            tokens.of(Realm.CONSUMER).revoke(consumer);
            tokens.of(Realm.MERCHANT).revoke(cashier);
        }
    }

    private JsonNode purchaseHttp(String token, CheckoutPort.CheckoutQuote quote) throws Exception {
        String path = "/mp/checkout/" + quote.operation().operationNo();
        var read =
                mvc.perform(get(path).header("Authorization", "Bearer " + token))
                        .andReturn()
                        .getResponse();
        assertThat(read.getStatus()).isEqualTo(200);
        var body = json.readTree(read.getContentAsString());
        assertThat(body.path("code").asInt()).isZero();
        assertThat(body.path("data").path("quoteNo").asString()).isEqualTo(quote.quoteNo());
        var confirmation =
                postHttp(
                        token,
                        path + "/confirmations",
                        "confirm-" + sku,
                        Map.of(
                                "quoteNo",
                                quote.quoteNo(),
                                "quoteRevision",
                                quote.quoteRevision(),
                                "quoteHash",
                                quote.quoteHash()));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                checkout.place(
                                                        new CheckoutPort.PlaceCheckoutReq(
                                                                quote.operation(),
                                                                confirmation
                                                                        .path("confirmationNo")
                                                                        .asString(),
                                                                "forbidden-place-" + sku))))
                .isInstanceOf(BizException.class);
        return postHttp(
                token,
                path + "/orders",
                "place-" + sku,
                Map.of("confirmationNo", confirmation.path("confirmationNo").asString()));
    }

    private JsonNode postHttp(String token, String path, String key, Object body) throws Exception {
        var response =
                mvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + token)
                                        .header("X-Store-No", store)
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var result = json.readTree(response.getContentAsString());
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        return result.path("data");
    }

    private Object openUser() {
        return asMerchant(
                () ->
                        call(
                                bean("WorkOrderService"),
                                "openWorkOrder",
                                entity,
                                store,
                                actor,
                                dto(
                                        "OpenWorkOrderReq",
                                        reference,
                                        store,
                                        List.of(dto("AppointmentItemReq", sku, 1)),
                                        null),
                                "open-" + sku));
    }

    private Object submission(Object root) {
        return submission(root, "ONLINE");
    }

    private Object submission(Object root, String payMode) {
        Object task = ((List<?>) value(root, "taskNos")).getFirst();
        return dto(
                "SubmitServicesReq",
                0L,
                1L,
                List.of(dto("SubmitServicesReq$TaskSelection", task, 0L, 0L)),
                List.of(),
                dto(
                        "SubmitServicesReq$SourceSelection",
                        "PREPARE",
                        reference,
                        payMode,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of()));
    }

    private Object submit(String number, Object request) {
        return call(
                bean("WorkOrderService"),
                "submitServices",
                entity,
                store,
                actor,
                number,
                request,
                "submit-" + sku);
    }

    private <T> T asManager(String manager, Supplier<T> action) {
        return DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", manager), action);
    }

    private Object bean(String name) {
        return context.getBean(type("service." + name));
    }

    private Class<?> type(String name) {
        try {
            return Class.forName("ai.neargo.shop.beauty." + name);
        } catch (ClassNotFoundException absent) {
            throw new AssertionError(absent);
        }
    }

    private Object dto(String name, Object... arguments) {
        try {
            var constructor =
                    Arrays.stream(type("dto." + name).getConstructors())
                            .filter(candidate -> candidate.getParameterCount() == arguments.length)
                            .findFirst()
                            .orElseThrow();
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw cause(failure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Object value(Object record, String name) {
        return call(record, name);
    }

    private Object call(Object receiver, String name, Object... arguments) {
        try {
            var method =
                    Arrays.stream(receiver.getClass().getMethods())
                            .filter(
                                    candidate ->
                                            candidate.getName().equals(name)
                                                    && candidate.getParameterCount()
                                                            == arguments.length)
                            .findFirst()
                            .orElseThrow();
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw cause(failure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private RuntimeException cause(InvocationTargetException failure) {
        if (failure.getCause() instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure.getCause() instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure.getCause());
    }
}
