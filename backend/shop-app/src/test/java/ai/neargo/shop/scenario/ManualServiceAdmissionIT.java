package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.portal.internal.InternalManualServiceAdmissionController;
import ai.neargo.shop.spi.product.PayModePort;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionPort.CurrentState;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Outcome;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StorePayPort;
import ai.neargo.shop.trade.order.points.OrderPointsCoordinator;
import ai.neargo.shop.trade.port.CheckoutPortImpl;
import ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.CheckoutServiceImpl;
import ai.neargo.shop.trade.service.impl.ManualServiceAdmissionServiceImpl;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Real catalog, current C consent and common order creation; no merchant impersonation. */
class ManualServiceAdmissionIT extends CheckoutFlowFixture {
    @Autowired ManualServiceAdmissionPort manual;
    @Autowired OrderService orderOwner;
    @Autowired CheckoutService checkoutOwner;
    @Autowired ManualServiceAdmissionService manualOwner;
    @MockitoSpyBean MerchantOperatorAccessPort canonicalOperators;
    @MockitoSpyBean PayModePort paymentModes;
    @MockitoSpyBean StorePayPort storePayments;
    @MockitoSpyBean MerchantQueryPort merchants;
    @MockitoSpyBean OrderPointsCoordinator points;
    @MockitoSpyBean SettlePort financial;
    @MockitoSpyBean PaymentOperationPort paymentOperations;
    @MockitoSpyBean StockDispositionPort stockBoundary;

    @BeforeEach
    void actualSourceOrigins() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        jdbc.update(
                "INSERT INTO"
                        + " mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,created_at,"
                        + "updated_at) VALUES (?,?,'BUSINESS_LICENSE','Current test qualification',"
                        + "'TEST-FIXTURE','VALID',NOW(),NOW())",
                "license-" + sku,
                entity);
        actualOrigin(orderOwner, OrderServiceImpl.class);
        actualOrigin(checkout, CheckoutPortImpl.class);
        actualOrigin(manual, ManualServiceAdmissionPortImpl.class);
        actualOrigin(checkoutOwner, CheckoutServiceImpl.class);
        actualOrigin(manualOwner, ManualServiceAdmissionServiceImpl.class);
    }

    private static void actualOrigin(Object provider, Class<?> expected) {
        var type = AopUtils.getTargetClass(provider);
        assertThat(type).isEqualTo(expected);
        String source = type.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(source).contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
        System.out.println("TASK64_CODE_SOURCE " + type.getName() + " " + source);
    }

    @Override
    protected <T> T asMerchant(Supplier<T> work) {
        return currentCashier(tokens, actor, work);
    }

    static <T> T currentCashier(TokenStores tokens, String actor, Supplier<T> work) {
        var user = LoginUser.merchant(actor, "manual cashier");
        var pool = tokens.of(Realm.MERCHANT);
        String token = pool.issue(new SessionData(user));
        var original = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        authentication.setDetails(token);
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(original);
            pool.revoke(token);
        }
    }

    @Override
    protected CheckoutPort.PrepareCheckoutReq request(String coupon) {
        return new CheckoutPort.PrepareCheckoutReq(
                new CheckoutPort.OperationRef("BEAUTY", "manual-" + sku),
                "prepare-" + sku,
                reference,
                1,
                "a".repeat(64),
                List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 1)),
                "OFFLINE",
                coupon,
                Map.of(),
                null);
    }

    @Test
    void currentBuyerPurchaseAndCashierReceiptCreateOneManualSource() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        var r = asMerchant(() -> manual.confirm(scope, receipt(q, revision(o.orderNo()))));
        assertThat(r.admissionState()).isEqualTo("ADMITTED");
        assertThat(r.receiptProvenance()).isEqualTo("MANUAL_DECLARATION");
        assertThat(r.manualDeclarationNo()).isNotBlank();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status,pay_channel,pay_trade_no,admission_kind FROM"
                                        + " ord_order WHERE order_no=?",
                                o.orderNo()))
                .containsEntry("status", "PAID")
                .containsEntry("pay_channel", null)
                .containsEntry("pay_trade_no", null)
                .containsEntry("admission_kind", "MANUAL_OFFLINE");
        var s =
                asMerchant(
                        () ->
                                manual.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                q.operation(), o.subOrderNo())));
        assertThat(s.lines()).hasSize(1);
        assertThat(s.lines().getFirst().availableQuantity()).isEqualTo(1);
        assertThat(s.orderNo()).isEqualTo(o.orderNo());
    }

    @Test
    void manualQuoteAndPlacementNeedNoPayConfiguration() {
        var violation = new AssertionError("manual purchase crossed a payment-only boundary");
        doThrow(violation).when(paymentModes).availablePayModes(anyString(), anyString());
        doThrow(violation)
                .when(paymentModes)
                .availablePayModes(anyString(), anyString(), anyString());
        doThrow(violation).when(paymentModes).expressUnderTest(anyString());
        doThrow(violation).when(storePayments).offlinePayEnabled(anyString());
        doThrow(violation).when(storePayments).codEnabled(anyString());
        doThrow(violation).when(merchants).payCapabilityOf(anyString(), anyString());
        doThrow(violation).when(points).decide(any());
        doThrow(violation).when(financial).initPayment(any());
        doThrow(violation).when(financial).openPayment(any());
        doThrow(violation).when(paymentOperations).prepare(any());
        doThrow(violation).when(paymentOperations).initiate(anyString());
        jdbc.update("UPDATE prd_goods SET pay_modes='[\"ONLINE\"]' WHERE goods_no=?", goods);
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        assertThat(asBuyer(() -> checkout.place(place(q, c))).orderNo()).isNotBlank();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                        + " operation_no=?",
                                Long.class,
                                q.operation().operationNo()))
                .isZero();
    }

    @Test
    void cashierCannotConfirmOrPlaceForBuyer() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        assertThatThrownBy(() -> asMerchant(() -> confirm(q))).isInstanceOf(BizException.class);
        var c = asBuyer(() -> confirm(q));
        assertThatThrownBy(() -> asMerchant(() -> checkout.place(place(q, c))))
                .isInstanceOf(BizException.class);
        assertThat(orders()).isZero();
    }

    @Test
    void staleOrChangedQuoteCannotBePurchased() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        jdbc.update("UPDATE prd_sku SET price=10000 WHERE sku_no=?", sku);
        assertThatThrownBy(() -> asBuyer(() -> checkout.place(place(q, c))))
                .isInstanceOf(BizException.class);
        assertThat(orders()).isZero();
    }

    @Test
    void cashPermissionAndExactAmountAreRequired() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        var r = receipt(q, revision(o.orderNo()));
        var wrong =
                new ConfirmManualReceipt(
                        r.operation(),
                        r.requestKey(),
                        r.expectedOrderRevision(),
                        r.method(),
                        r.declaredMinor() + 1,
                        r.currency(),
                        r.receivedAt(),
                        r.note());
        assertThatThrownBy(() -> asMerchant(() -> manual.confirm(scope, wrong)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                new ConfirmManualReceipt(
                                        r.operation(),
                                        r.requestKey(),
                                        r.expectedOrderRevision(),
                                        r.method(),
                                        r.declaredMinor(),
                                        "USD",
                                        r.receivedAt(),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        var future =
                new ConfirmManualReceipt(
                        r.operation(),
                        r.requestKey(),
                        r.expectedOrderRevision(),
                        r.method(),
                        r.declaredMinor(),
                        "CNY",
                        r.receivedAt().plusSeconds(3600),
                        null);
        assertThatThrownBy(() -> asMerchant(() -> manual.confirm(scope, future)))
                .isInstanceOf(BizException.class);
        var foreign = new CheckoutPort.CheckoutScope("MAIN", entity, "foreign-store", actor);
        assertThatThrownBy(() -> asMerchant(() -> manual.confirm(foreign, r)))
                .isInstanceOf(BizException.class);
        assertThat(declarations(q)).isZero();
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", actor);
        jdbc.update(
                "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at) VALUES"
                        + " (?,'MANUAL_CASHIER','manual"
                        + " cashier','[\"pay:cash:receive\",\"biz:order:view\"]',NOW(),NOW())",
                entity);
        jdbc.update(
                "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) "
                        + "VALUES (?,?,'MANUAL_CASHIER',NOW(),NOW())",
                actor,
                store);
        asMerchant(
                () -> {
                    assertThatThrownBy(() -> manual.confirm(scope, r))
                            .isInstanceOf(BizException.class);
                    jdbc.update(
                            "UPDATE mch_role SET"
                                    + " perms='[\"pay:cash:receive\","
                                    + "\"trade:manual-service:confirm\",\"biz:order:view\"]'"
                                    + " WHERE entity_no=? AND role_code='MANUAL_CASHIER'",
                            entity);
                    manual.confirm(scope, r);
                    assertThatThrownBy(() -> manual.confirm(scope, wrong))
                            .isInstanceOf(BizException.class);
                    jdbc.update(
                            "UPDATE mch_role SET perms='[\"pay:cash:receive\",\"biz:order:view\"]' "
                                    + "WHERE entity_no=? AND role_code='MANUAL_CASHIER'",
                            entity);
                    assertThatThrownBy(() -> manual.confirm(scope, r))
                            .isInstanceOf(BizException.class);
                    return null;
                });
        assertThat(declarations(q)).isEqualTo(1);
        jdbc.update("UPDATE mch_account SET is_owner=1 WHERE mch_account_no=?", actor);
        var resolutions = new AtomicInteger();
        String userActor = actor + "-user";
        // Real owner queries return account A; switch its actual USER membership after the initial
        // full permission resolution and before the immediate pre-TX resolution.
        doAnswer(
                        invocation -> {
                            var query =
                                    (MerchantOperatorAccessPort.Query) invocation.getArgument(0);
                            var resolved = invocation.callRealMethod();
                            if (query.subjectKind().equals("USR")
                                    && query.permission().equals(BizPerms.MANUAL_CONFIRM)
                                    && resolutions.incrementAndGet() == 2) {
                                jdbc.update(
                                        "UPDATE mch_account SET user_no=? WHERE mch_account_no=?",
                                        actor + "-detached",
                                        actor);
                                jdbc.update(
                                        "INSERT INTO"
                                                + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,"
                                                + "created_at,updated_at) VALUES"
                                                + " (?,?,?,1,'ACTIVE',NOW(),NOW())",
                                        entity,
                                        actor + "-replacement",
                                        userActor);
                            }
                            return resolved;
                        })
                .when(canonicalOperators)
                .resolveAuthorizedOperator(any(MerchantOperatorAccessPort.Query.class));
        var userScope = new CheckoutPort.CheckoutScope("MAIN", entity, store, userActor);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                "CONFIRM",
                                new DelegatedIdentity("MAIN", "MERCHANT", "USR", userActor),
                                entity,
                                store,
                                q.operation().operationNo()),
                        new Scoped<>(userScope, r));
        var controller =
                new InternalManualServiceAdmissionController(manual, "task64-account-switch");
        assertThat(controller.confirm(call, "task64-account-switch").getStatusCode().value())
                .isEqualTo(403);
        assertThat(resolutions).hasValueGreaterThanOrEqualTo(2);
        assertThat(declarations(q)).isEqualTo(1);
    }

    @Test
    void lostReplyReplaysOriginalDeclaration() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        var r = receipt(q, revision(o.orderNo()));
        // Real controller installs verified token/route purpose; no HOST session is forwarded.
        var controller =
                new InternalManualServiceAdmissionController(manual, "task64-bound-test-key");
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                "CONFIRM",
                                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor),
                                entity,
                                store,
                                q.operation().operationNo()),
                        new Scoped<>(scope, r));
        var response = controller.confirm(call, "task64-bound-test-key");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var original = (ManualAdmissionView) ((InternalReply<?>) response.getBody()).result();
        assertThat(original.admissionState()).isEqualTo("ADMITTED");
        assertThat(BoundActionContext.present()).isFalse();
        var changed =
                new ConfirmManualReceipt(
                        r.operation(),
                        r.requestKey(),
                        r.expectedOrderRevision(),
                        r.method(),
                        r.declaredMinor(),
                        r.currency(),
                        r.receivedAt(),
                        "changed");
        assertThat(
                        controller
                                .confirm(
                                        new InternalCall<>(
                                                call.binding(), new Scoped<>(scope, changed)),
                                        "task64-bound-test-key")
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no=?", actor);
        assertThat(controller.confirm(call, "task64-bound-test-key").getStatusCode().value())
                .isEqualTo(403);
        jdbc.update("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no=?", actor);
        assertThat(asMerchant(() -> manual.confirm(scope, r)).manualDeclarationNo())
                .isEqualTo(original.manualDeclarationNo());
        assertThat(
                        asMerchant(
                                        () ->
                                                manual.query(
                                                        scope,
                                                        new ManualLookup(
                                                                q.operation(),
                                                                null,
                                                                r.requestKey())))
                                .manualDeclarationNo())
                .isEqualTo(original.manualDeclarationNo());
        var replacement =
                new ConfirmManualReceipt(
                        r.operation(),
                        "different-key",
                        r.expectedOrderRevision(),
                        r.method(),
                        r.declaredMinor(),
                        r.currency(),
                        r.receivedAt(),
                        null);
        assertThatThrownBy(() -> asMerchant(() -> manual.confirm(scope, replacement)))
                .isInstanceOf(BizException.class);
        assertThat(declarations(q)).isEqualTo(1);
    }

    @Test
    void stockUnknownKeepsReceiptAndBlocksAdmission() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        jdbc.update("DELETE FROM prd_stock_lock WHERE lock_no=?", o.orderNo());
        var r = asMerchant(() -> manual.confirm(scope, receipt(q, revision(o.orderNo()))));
        assertThat(r.admissionState()).isNotEqualTo("ADMITTED");
        assertThat(r.manualDeclarationNo()).isNotBlank();
        assertThat(declarations(q)).isEqualTo(1);
    }

    @Test
    void unchangedPendingOriginalDoesNotStarveNextBoundedRecoverySlice() {
        var allowSecond = new AtomicBoolean(false);
        var firstOrder = new AtomicReference<String>();
        doAnswer(
                        invocation -> {
                            Lookup lookup = invocation.getArgument(0);
                            if (allowSecond.get()
                                    && !lookup.original().orderNo().equals(firstOrder.get())) {
                                return invocation.callRealMethod();
                            }
                            return new Disposition(
                                    lookup.original(),
                                    lookup.operationNo(),
                                    Outcome.UNKNOWN,
                                    CurrentState.MISSING,
                                    List.of(),
                                    "a".repeat(64),
                                    testClock.at,
                                    "STOCK_OWNER_UNAVAILABLE");
                        })
                .when(stockBoundary)
                .observeOriginal(any());
        var firstQuote = asMerchant(() -> manual.prepare(scope, request(null)));
        var firstConsent = asBuyer(() -> confirm(firstQuote));
        var first = asBuyer(() -> checkout.place(place(firstQuote, firstConsent)));
        firstOrder.set(first.orderNo());
        asMerchant(() -> manual.confirm(scope, receipt(firstQuote, revision(first.orderNo()))));
        asMerchant(
                () ->
                        manual.source(
                                scope,
                                new CheckoutPort.SourceLookup(
                                        firstQuote.operation(), first.subOrderNo())));
        var secondRequest =
                new CheckoutPort.PrepareCheckoutReq(
                        new CheckoutPort.OperationRef("BEAUTY", "second-" + sku),
                        "prepare-second-" + sku,
                        reference,
                        1,
                        "a".repeat(64),
                        List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 1)),
                        "OFFLINE",
                        null,
                        Map.of(),
                        null);
        var secondQuote = asMerchant(() -> manual.prepare(scope, secondRequest));
        var secondConsent =
                asBuyer(
                        () ->
                                checkout.confirm(
                                        new CheckoutPort.ConfirmCheckoutReq(
                                                secondQuote.operation(),
                                                secondQuote.quoteNo(),
                                                secondQuote.quoteRevision(),
                                                secondQuote.quoteHash(),
                                                "confirm-second-" + sku)));
        var second =
                asBuyer(
                        () ->
                                checkout.place(
                                        new CheckoutPort.PlaceCheckoutReq(
                                                secondQuote.operation(),
                                                secondConsent.confirmationNo(),
                                                "place-second-" + sku)));
        asMerchant(() -> manual.confirm(scope, receipt(secondQuote, revision(second.orderNo()))));
        jdbc.update(
                "UPDATE ord_manual_service_admission SET next_retry_at=NOW(3)-INTERVAL 2 MINUTE"
                        + " WHERE order_no=?",
                first.orderNo());
        jdbc.update(
                "UPDATE ord_manual_service_admission SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE"
                        + " WHERE order_no=?",
                second.orderNo());
        var admissionBefore =
                jdbc.queryForMap(
                        "SELECT admission_revision,stock_observation_hash FROM"
                                + " ord_manual_service_admission WHERE order_no=?",
                        first.orderNo());
        var sourceBefore =
                jdbc.queryForMap(
                        "SELECT"
                                + " source_revision,source_observation_hash,"
                                + "manual_observation_revision,manual_observation_hash"
                                + " FROM ord_checkout_operation WHERE operation_no=?",
                        firstQuote.operation().operationNo());
        var actionsBefore =
                jdbc.queryForList(
                        "SELECT * FROM ord_manual_service_admission_action WHERE order_no=? ORDER"
                                + " BY id",
                        first.orderNo());
        testClock.at = Instant.now();
        var future = testClock.at.plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        allowSecond.set(true);
        assertThat(manualOwner.recoverPending(1)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT next_retry_at FROM ord_manual_service_admission"
                                                + " WHERE order_no=?",
                                        Timestamp.class,
                                        first.orderNo())
                                .toInstant())
                .isEqualTo(future);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT admission_revision,stock_observation_hash FROM"
                                        + " ord_manual_service_admission WHERE order_no=?",
                                first.orderNo()))
                .isEqualTo(admissionBefore);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT"
                                        + " source_revision,source_observation_hash,"
                                        + "manual_observation_revision,manual_observation_hash"
                                        + " FROM ord_checkout_operation WHERE operation_no=?",
                                firstQuote.operation().operationNo()))
                .isEqualTo(sourceBefore);
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM ord_manual_service_admission_action WHERE order_no=?"
                                        + " ORDER BY id",
                                first.orderNo()))
                .isEqualTo(actionsBefore);
        assertThat(manualOwner.recoverPending(1)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT phase FROM ord_manual_service_admission WHERE order_no=?",
                                String.class,
                                second.orderNo()))
                .isEqualTo("ADMITTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                second.orderNo()))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void manualSourceDoesNotRewriteFinancialObservation() {
        var q =
                assertDoesNotThrow(
                        () -> asMerchant(() -> manual.prepare(scope, request(null))),
                        "actual manual quote must not require payment configuration");
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        asMerchant(() -> manual.confirm(scope, receipt(q, revision(o.orderNo()))));
        var before =
                jdbc.queryForMap(
                        "SELECT source_revision,source_observation_hash FROM ord_checkout_operation"
                                + " WHERE operation_no=?",
                        q.operation().operationNo());
        var old =
                asMerchant(
                        () ->
                                checkout.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                q.operation(), o.subOrderNo())));
        assertThat(old.financialState()).isEqualTo("UNKNOWN");
        assertThat(old.lines()).isEmpty();
        var lookup = new CheckoutPort.SourceLookup(q.operation(), o.subOrderNo());
        var initial = asMerchant(() -> manual.source(scope, lookup));
        assertThat(initial.admissionState()).isEqualTo("ADMITTED");
        assertThat(asMerchant(() -> manual.source(scope, lookup)).manualObservationHash())
                .as("operation observation writes must not invalidate their own hash")
                .isEqualTo(initial.manualObservationHash());
        assertThat(
                        jdbc.update(
                                "UPDATE ord_checkout_line SET version=version+1 WHERE"
                                        + " operation_no=?",
                                q.operation().operationNo()))
                .isEqualTo(1);
        var mapped = asMerchant(() -> manual.source(scope, lookup));
        assertThat(mapped.manualObservationRevision())
                .isEqualTo(initial.manualObservationRevision() + 1);
        assertThat(mapped.manualObservationHash()).isNotEqualTo(initial.manualObservationHash());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT source_revision,source_observation_hash FROM"
                                        + " ord_checkout_operation WHERE operation_no=?",
                                q.operation().operationNo()))
                .isEqualTo(before);
        assertThat(
                        jdbc.update(
                                "UPDATE ord_item SET version=version+1 WHERE sub_order_no=?",
                                o.subOrderNo()))
                .isEqualTo(1);
        var item = asMerchant(() -> manual.source(scope, lookup));
        assertThat(item.manualObservationRevision())
                .isEqualTo(mapped.manualObservationRevision() + 1);
        assertThat(item.manualObservationHash()).isNotEqualTo(mapped.manualObservationHash());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT source_revision,source_observation_hash FROM"
                                        + " ord_checkout_operation WHERE operation_no=?",
                                q.operation().operationNo()))
                .isEqualTo(before);
        assertThat(
                        jdbc.update(
                                "UPDATE ord_sub_order SET version=version+1 WHERE sub_order_no=?",
                                o.subOrderNo()))
                .isEqualTo(1);
        var child = asMerchant(() -> manual.source(scope, lookup));
        assertThat(child.manualObservationRevision())
                .isEqualTo(item.manualObservationRevision() + 1);
        assertThat(child.manualObservationHash()).isNotEqualTo(item.manualObservationHash());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT source_revision,source_observation_hash FROM"
                                        + " ord_checkout_operation WHERE operation_no=?",
                                q.operation().operationNo()))
                .isEqualTo(before);
    }

    @Test
    void cancelAndManualReceiptHaveOneWinner() throws Exception {
        var q = assertDoesNotThrow(() -> asMerchant(() -> manual.prepare(scope, request(null))));
        var c = asBuyer(() -> confirm(q));
        var o = asBuyer(() -> checkout.place(place(q, c)));
        var request = receipt(q, revision(o.orderNo()));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var rootLock = dataSource.getConnection();
                var threads = Executors.newFixedThreadPool(2)) {
            rootLock.setAutoCommit(false);
            try (var lock =
                    rootLock.prepareStatement(
                            "SELECT id FROM ord_order WHERE order_no=? FOR UPDATE")) {
                lock.setString(1, o.orderNo());
                lock.executeQuery().close();
            }
            var confirm =
                    threads.submit(
                            () -> {
                                entered.countDown();
                                release.await();
                                try {
                                    return asMerchant(() -> manual.confirm(scope, request));
                                } catch (BizException denied) {
                                    return denied;
                                }
                            });
            var cancel =
                    threads.submit(
                            () -> {
                                entered.countDown();
                                release.await();
                                try {
                                    return asBuyer(
                                            () -> orderOwner.cancel(o.orderNo(), "manual race"));
                                } catch (BizException denied) {
                                    return denied;
                                }
                            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            rootLock.commit();
            confirm.get(20, TimeUnit.SECONDS);
            cancel.get(20, TimeUnit.SECONDS);
        }
        String status =
                jdbc.queryForObject(
                        "SELECT status FROM ord_order WHERE order_no=?", String.class, o.orderNo());
        long declarations = declarations(q);
        assertThat(status).isIn("PAID", "CANCELLED");
        assertThat(declarations).isEqualTo("PAID".equals(status) ? 1 : 0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                                String.class,
                                o.subOrderNo()))
                .isEqualTo("PAID".equals(status) ? "FULFILLING" : "CANCELLED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                o.orderNo()))
                .containsExactly("PAID".equals(status) ? "CONFIRMED" : "RELEASED");
        assertThat(jdbc.queryForMap("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku))
                .containsEntry("stock", "PAID".equals(status) ? 99 : 100)
                .containsEntry("locked_stock", 0);
    }

    long revision(String order) {
        return jdbc.queryForObject(
                "SELECT version FROM ord_order WHERE order_no=?", Long.class, order);
    }

    long orders() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ord_order WHERE user_no=?", Long.class, buyer);
    }

    long declarations(CheckoutPort.CheckoutQuote q) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ord_manual_service_admission WHERE operation_no=?",
                Long.class,
                q.operation().operationNo());
    }

    ConfirmManualReceipt receipt(CheckoutPort.CheckoutQuote q, long revision) {
        return new ConfirmManualReceipt(
                q.operation(),
                "receipt-" + sku,
                revision,
                ManualMethod.CASH,
                101,
                "CNY",
                testClock.at.minusMillis(1).truncatedTo(ChronoUnit.MILLIS),
                null);
    }
}
