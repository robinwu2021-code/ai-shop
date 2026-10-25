package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invbridge.port.OriginalStockDispositionPort;
import ai.neargo.shop.member.port.MemberCustomerIdentityPortImpl;
import ai.neargo.shop.member.port.MemberGuestRegistrationPortImpl;
import ai.neargo.shop.member.service.impl.MemberCodeResolveServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.product.PayModePort;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.ConfirmCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PlaceCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.user.PersonPort;
import ai.neargo.shop.spi.user.StorePayPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.points.OrderPointsCoordinator;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;
import ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;
import ai.neargo.shop.user.port.PersonPortImpl;
import ai.neargo.shop.user.port.UserScopeFactsPortImpl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Real member, catalog, ordinary writer and original stock; the only session is the merchant's. */
class ManualCounterPurchaseIT extends CheckoutFlowFixture {
    @Autowired protected ManualServiceAdmissionPort manual;
    @Autowired CheckoutAccessPolicy counterAccess;
    @Autowired protected GuestRegistrationPort guests;
    @MockitoSpyBean protected CustomerIdentityPort customers;
    @Autowired OrderService writer;
    @Autowired PersonPort people;
    @Autowired UserScopeFactsPort userFacts;
    @MockitoSpyBean protected StockDispositionPort originalStock;
    @MockitoSpyBean PayModePort payModes;
    @MockitoSpyBean StorePayPort storePayments;
    @MockitoSpyBean OrderPointsCoordinator points;
    @MockitoSpyBean SettlePort financial;
    @MockitoSpyBean PaymentOperationPort paymentOperations;
    @Autowired CheckoutMappers.OperationMapper typedOperations;
    @Autowired CheckoutMappers.ConfirmationMapper typedConfirmations;
    @Autowired OrderMapper typedParents;
    @Autowired SubOrderMapper typedChildren;
    @Autowired CreateIntentMapper typedIntents;
    @Autowired ManualAdmissionMapper typedDeclarations;
    protected String member;

    @BeforeEach
    void canonicalCounterFixture() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        origin(manual, ManualServiceAdmissionPortImpl.class);
        origin(customers, MemberCustomerIdentityPortImpl.class);
        origin(guests, MemberGuestRegistrationPortImpl.class);
        origin(identities, MemberCodeResolveServiceImpl.class);
        origin(people, PersonPortImpl.class);
        origin(userFacts, UserScopeFactsPortImpl.class);
        origin(writer, OrderServiceImpl.class);
        origin(originalStock, OriginalStockDispositionPort.class);
        jdbc.update(
                "INSERT INTO mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,"
                        + "created_at,updated_at) VALUES (?,?,'BUSINESS_LICENSE','counter license',"
                        + "'FIXTURE','VALID',NOW(),NOW())",
                "license-" + sku,
                entity);
        member = "member-" + sku;
        String person = "person-" + sku;
        jdbc.update(
                "INSERT INTO usr_person(person_no,phone_hash,user_no,status,created_at,updated_at)"
                        + " VALUES (?,?,?,'ACTIVE',NOW(),NOW())",
                person,
                CheckoutDigests.hash(person),
                buyer);
        jdbc.update(
                "INSERT INTO mbr_member(member_no,entity_no,person_no,status,source,joined_at,"
                        + "created_at,updated_at)"
                        + " VALUES (?,?,?,'ACTIVE','MANUAL',?,NOW(),NOW())",
                member,
                entity,
                person,
                System.currentTimeMillis());
    }

    @Override
    protected <T> T asMerchant(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, actor, work);
    }

    protected Registration registerGuest() {
        return asMerchant(
                () ->
                        guests.register(
                                guestScope(),
                                new RegisterCommand(null, null, true, "guest-" + sku)));
    }

    protected Scope guestScope() {
        return new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
    }

    protected PrepareCounterReq counterRequest(String suffix, CounterCustomerRef customer) {
        return new PrepareCounterReq(
                new OperationRef("BEAUTY", "counter-" + suffix + '-' + sku),
                "prepare-" + suffix + '-' + sku,
                customer,
                1,
                "a".repeat(64),
                List.of(new CheckoutLineReq("service-line", sku, 1)),
                null,
                Map.of(),
                null);
    }

    protected CounterCheckoutQuote prepare(PrepareCounterReq request) {
        return asMerchant(
                () ->
                        assertDoesNotThrow(
                                () -> manual.prepareCounter(scope, request),
                                "real merchant counter prepare must not require a consumer session"));
    }

    protected CounterConfirmation accept(CounterCheckoutQuote quote) {
        return asMerchant(
                () ->
                        manual.acceptCounter(
                                scope,
                                new AcceptCounterReq(
                                        quote.quote().operation(),
                                        "accept-" + quote.quote().operation().operationNo(),
                                        quote.quote().quoteNo(),
                                        quote.quote().quoteRevision(),
                                        quote.quote().quoteHash(),
                                        quote.party().identityRevision(),
                                        testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                        "MERCHANT_RECORDED_IN_PERSON")));
    }

    protected CheckoutResult place(CounterConfirmation confirmation) {
        return asMerchant(
                () ->
                        manual.placeCounter(
                                scope,
                                new PlaceCounterReq(
                                        confirmation.operation(),
                                        confirmation.confirmationNo(),
                                        "place-" + confirmation.operation().operationNo())));
    }

    @Test
    void currentAccountRevocationStopsCounterAfterMemberIO() {
        doAnswer(
                        call -> {
                            var actual = call.callRealMethod();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            jdbc.update(
                                    "UPDATE mch_account SET status='DISABLED' WHERE mch_account_no=?",
                                    actor);
                            return actual;
                        })
                .when(customers)
                .resolveCounterReference(any(), any());
        var request =
                counterRequest(
                        "revoked-account", new CounterCustomerRef("USER", member, null, null));
        try {
            assertThatThrownBy(() -> asMerchant(() -> manual.prepareCounter(scope, request)))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            failure ->
                                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        } finally {
            jdbc.update("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no=?", actor);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_operation WHERE operation_no=?",
                                Long.class,
                                request.operation().operationNo()))
                .isZero();
        noOrderOrStock(request.operation());
    }

    @Test
    void counterAcceptanceRequiresOriginalCashierNamespace() {
        var registration = registerGuest();
        var quote =
                prepare(
                        counterRequest(
                                "original-cashier",
                                new CounterCustomerRef(
                                        "GUEST",
                                        null,
                                        registration.guestNo(),
                                        registration.claimToken())));
        String alternate = "alternate-" + sku;
        jdbc.update(
                "INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                        + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                alternate,
                alternate + "-user");
        var alternateScope = new CheckoutScope("MAIN", entity, store, alternate);
        var actual =
                ManualServiceAdmissionIT.currentCashier(
                        tokens,
                        alternate,
                        () -> counterAccess.requireCurrentCashier(alternateScope));
        assertThat(actual.actorNo()).isEqualTo(alternate);
        assertThat(actual.accountNo()).isEqualTo(alternate).isNotEqualTo(actor);
        var acceptance =
                new AcceptCounterReq(
                        quote.quote().operation(),
                        "first-acceptance-" + sku,
                        quote.quote().quoteNo(),
                        quote.quote().quoteRevision(),
                        quote.quote().quoteHash(),
                        quote.party().identityRevision(),
                        testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                        "MERCHANT_RECORDED_IN_PERSON");
        assertThatThrownBy(
                        () ->
                                ManualServiceAdmissionIT.currentCashier(
                                        tokens,
                                        alternate,
                                        () -> manual.acceptCounter(alternateScope, acceptance)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_confirmation WHERE operation_no=?",
                                Long.class,
                                quote.quote().operation().operationNo()))
                .isZero();
        var original = asMerchant(() -> manual.acceptCounter(scope, acceptance));
        assertThat(original.party()).isEqualTo(quote.party());
        assertThat(
                        ManualServiceAdmissionIT.currentCashier(
                                        tokens,
                                        alternate,
                                        () ->
                                                manual.readCounterQuote(
                                                        alternateScope, quote.quote().operation()))
                                .party())
                .isEqualTo(quote.party());
    }

    @Test
    void differentAuthorizedCashierResumesOriginalOnce() {
        var quote =
                prepare(
                        counterRequest(
                                "cashier-resume",
                                new CounterCustomerRef("USER", member, null, null)));
        var accepted = accept(quote);
        var request =
                new PlaceCounterReq(
                        accepted.operation(), accepted.confirmationNo(), "cashier-place-" + sku);
        var lostReply = new AtomicBoolean(false);
        var firstExternalObservation = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            var actual =
                                    (StockDispositionPort.ReservationView) call.callRealMethod();
                            assertThat(actual.outcome()).isEqualTo("RESERVED");
                            lostReply.set(true);
                            throw new IllegalStateException("lost actual committed reserve reply");
                        })
                .when(originalStock)
                .reserveOriginal(any());
        doAnswer(
                        call -> {
                            if (lostReply.get() && firstExternalObservation.getAndSet(false)) {
                                throw new IllegalStateException(
                                        "first external observation unavailable");
                            }
                            return call.callRealMethod();
                        })
                .when(originalStock)
                .observeReservation(any());
        var pending =
                asMerchant(
                        () ->
                                assertDoesNotThrow(
                                        () -> manual.placeCounter(scope, request),
                                        "original authorized cashier must stage the genuine original"));
        assertThat(pending.phase()).isEqualTo("PLACING");
        var original =
                jdbc.queryForMap(
                        "SELECT pay_no,sub_order_no,plan_hash,counter_plan_json,"
                                + "reservation_operation_no FROM ord_create_intent WHERE operation_no=?",
                        accepted.operation().operationNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reservation_state FROM ord_create_intent WHERE pay_no=?",
                                String.class,
                                original.get("pay_no")))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                original.get("pay_no")))
                .containsExactly("LOCKED");
        var acceptance =
                jdbc.queryForMap(
                        "SELECT actor_no,actor_realm,actor_kind,account_no,"
                                + "authority_namespace_hash,accepted_at,request_hash"
                                + " FROM ord_checkout_confirmation"
                                + " WHERE confirmation_no=?",
                        accepted.confirmationNo());
        String alternate = "resume-" + sku;
        jdbc.update(
                "INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                        + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                alternate,
                alternate + "-user");
        var alternateScope = new CheckoutScope("MAIN", entity, store, alternate);
        var actual =
                ManualServiceAdmissionIT.currentCashier(
                        tokens,
                        alternate,
                        () -> counterAccess.requireCurrentCashier(alternateScope));
        assertThat(actual.actorNo()).isEqualTo(alternate).isNotEqualTo(actor);
        assertThat(actual.accountNo()).isEqualTo(alternate).isNotEqualTo(actor);
        assertThat(
                        ManualServiceAdmissionIT.currentCashier(
                                        tokens,
                                        alternate,
                                        () ->
                                                manual.readCounterQuote(
                                                        alternateScope, accepted.operation()))
                                .party())
                .isEqualTo(quote.party());
        System.out.println(
                "TASK66_CROSS_CASHIER actualAOriginalPending/heldOwnerRows/actualBAuthorized PASS");
        var created =
                ManualServiceAdmissionIT.currentCashier(
                        tokens,
                        alternate,
                        () ->
                                assertDoesNotThrow(
                                        () -> manual.placeCounter(alternateScope, request),
                                        "different current authorized cashier must resume the same accepted original"));
        assertThat(created.phase()).isEqualTo("CREATED");
        assertThat(created.orderNo()).isEqualTo(original.get("pay_no"));
        assertThat(created.subOrderNo()).isEqualTo(original.get("sub_order_no"));
        assertThat(asMerchant(() -> manual.placeCounter(scope, request)).orderNo())
                .isEqualTo(created.orderNo());
        assertThat(
                        ManualServiceAdmissionIT.currentCashier(
                                        tokens,
                                        alternate,
                                        () -> manual.placeCounter(alternateScope, request))
                                .orderNo())
                .isEqualTo(created.orderNo());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT pay_no,sub_order_no,plan_hash,counter_plan_json,"
                                        + "reservation_operation_no FROM ord_create_intent WHERE operation_no=?",
                                accepted.operation().operationNo()))
                .isEqualTo(original);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT actor_no,actor_realm,actor_kind,account_no,"
                                        + "authority_namespace_hash,accepted_at,request_hash"
                                        + " FROM ord_checkout_confirmation"
                                        + " WHERE confirmation_no=?",
                                accepted.confirmationNo()))
                .isEqualTo(acceptance);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                Long.class,
                                created.orderNo()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_create_intent WHERE operation_no=?",
                                Long.class,
                                accepted.operation().operationNo()))
                .isEqualTo(1);
        verify(originalStock, times(1)).reserveOriginal(any());
    }

    @Test
    void actualUserAndGuestBuyWithoutConsumerSession() {
        var violation = new AssertionError("counter purchase crossed a financial boundary");
        doThrow(violation).when(payModes).availablePayModes(anyString(), anyString());
        doThrow(violation).when(payModes).availablePayModes(anyString(), anyString(), anyString());
        doThrow(violation).when(storePayments).offlinePayEnabled(anyString());
        doThrow(violation).when(points).decide(any());
        doThrow(violation).when(financial).initPayment(any());
        doThrow(violation).when(financial).openPayment(any());
        doThrow(violation).when(paymentOperations).prepare(any());
        var canonical =
                asMerchant(
                        () ->
                                customers.resolveCounterReference(
                                        new EntitlementContext(
                                                "MAIN",
                                                actor,
                                                "member-lookup-" + sku,
                                                "b".repeat(64)),
                                        new CustomerIdentityPort.CounterReferenceQuery(
                                                entity, store, member)));
        assertThat(canonical.userNo()).isEqualTo(buyer);
        var registration = registerGuest();
        for (var selection :
                List.of(
                        new CounterCustomerRef("USER", member, null, null),
                        new CounterCustomerRef(
                                "GUEST",
                                null,
                                registration.guestNo(),
                                registration.claimToken()))) {
            String suffix = selection.kind().toLowerCase();
            var quote = prepare(counterRequest(suffix, selection));
            assertThat(quote.party().kind()).isEqualTo(selection.kind());
            assertThat(quote.party().userNo())
                    .isEqualTo("USER".equals(selection.kind()) ? buyer : null);
            assertThat(quote.party().guestNo()).isEqualTo(selection.guestNo());
            assertThat(quote.party().identityRevision())
                    .isEqualTo("USER".equals(selection.kind()) ? null : 1L);
            assertThat(asMerchant(() -> SecurityUtils.requireUser().realm()))
                    .isEqualTo(Realm.MERCHANT);
            var confirmation = accept(quote);
            var created = place(confirmation);
            assertThat(created.phase()).isEqualTo("CREATED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                    Integer.class,
                                    created.orderNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_sub_order WHERE order_no=?",
                                    Integer.class,
                                    created.orderNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_item WHERE order_no=?",
                                    Integer.class,
                                    created.orderNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_line WHERE operation_no=?",
                                    Integer.class,
                                    quote.quote().operation().operationNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_create_intent WHERE pay_no=?",
                                    Integer.class,
                                    created.orderNo()))
                    .isEqualTo(1);
            var party =
                    jdbc.queryForMap(
                            "SELECT customer_kind,user_no,guest_no,identity_revision FROM ord_order"
                                    + " WHERE order_no=?",
                            created.orderNo());
            assertThat(party)
                    .containsEntry("customer_kind", selection.kind())
                    .containsEntry("user_no", quote.party().userNo())
                    .containsEntry("guest_no", quote.party().guestNo());
            long revision =
                    jdbc.queryForObject(
                            "SELECT version FROM ord_order WHERE order_no=?",
                            Long.class,
                            created.orderNo());
            var receipt =
                    asMerchant(
                            () ->
                                    manual.confirm(
                                            scope,
                                            new ConfirmManualReceipt(
                                                    quote.quote().operation(),
                                                    "receipt-" + created.orderNo(),
                                                    revision,
                                                    ManualMethod.CASH,
                                                    quote.quote().dueMinor(),
                                                    "CNY",
                                                    testClock
                                                            .instant()
                                                            .truncatedTo(ChronoUnit.MILLIS),
                                                    null)));
            assertThat(receipt.admissionState()).isEqualTo("ADMITTED");
            var source =
                    asMerchant(
                            () ->
                                    manual.source(
                                            scope,
                                            new SourceLookup(
                                                    quote.quote().operation(),
                                                    created.subOrderNo())));
            assertThat(source.party()).isEqualTo(quote.party());
            assertThat(source.lines()).hasSize(1);
            assertThat(source.lines().getFirst().availableQuantity()).isEqualTo(1);
            assertThat(source.admissionKind()).isEqualTo("MANUAL_OFFLINE");
            assertActualRevision(
                    quote.quote().operation(), created.orderNo(), quote.party().identityRevision());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE operation_no=?",
                                    Integer.class,
                                    quote.quote().operation().operationNo()))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT command_json FROM ord_checkout_operation WHERE operation_no=?",
                                    String.class,
                                    quote.quote().operation().operationNo()))
                    .doesNotContain(registration.claimToken());
        }
        var legacyRequest =
                new PrepareCheckoutReq(
                        new OperationRef("BEAUTY", "legacy-revision-" + sku),
                        "legacy-prepare-" + sku,
                        reference,
                        1,
                        "a".repeat(64),
                        List.of(new CheckoutLineReq("service-line", sku, 1)),
                        "OFFLINE",
                        null,
                        Map.of(),
                        null);
        var legacyQuote = asMerchant(() -> manual.prepare(scope, legacyRequest));
        var legacyConfirmation = asBuyer(() -> confirm(legacyQuote));
        var legacyCreated = asBuyer(() -> checkout.place(place(legacyQuote, legacyConfirmation)));
        long legacyVersion =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        legacyCreated.orderNo());
        var legacyReceipt =
                asMerchant(
                        () ->
                                manual.confirm(
                                        scope,
                                        new ConfirmManualReceipt(
                                                legacyQuote.operation(),
                                                "legacy-receipt-" + sku,
                                                legacyVersion,
                                                ManualMethod.CASH,
                                                legacyQuote.dueMinor(),
                                                "CNY",
                                                testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                                null)));
        assertThat(legacyReceipt.admissionState()).isEqualTo("ADMITTED");
        assertActualRevision(legacyQuote.operation(), legacyCreated.orderNo(), 1L);
    }

    private void assertActualRevision(OperationRef reference, String orderNo, Long expected) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    var operation =
                            typedOperations.find(reference.caller(), reference.operationNo());
                    var confirmation =
                            typedConfirmations.selectOne(
                                    Wrappers.<OrdCheckoutConfirmation>lambdaQuery()
                                            .eq(
                                                    OrdCheckoutConfirmation::getConfirmationNo,
                                                    operation.getConfirmationNo()));
                    var parent =
                            typedParents.selectOne(
                                    Wrappers.<OrdOrder>lambdaQuery()
                                            .eq(OrdOrder::getOrderNo, orderNo));
                    var child =
                            typedChildren.selectOne(
                                    Wrappers.<OrdSubOrder>lambdaQuery()
                                            .eq(OrdSubOrder::getOrderNo, orderNo));
                    var intent =
                            typedIntents.selectOne(
                                    Wrappers.<OrdCreateIntent>lambdaQuery()
                                            .eq(OrdCreateIntent::getPayNo, orderNo));
                    var declaration =
                            typedDeclarations.selectOne(
                                    Wrappers.<OrdManualServiceAdmission>lambdaQuery()
                                            .eq(OrdManualServiceAdmission::getOrderNo, orderNo));
                    var revisions =
                            Arrays.asList(
                                    operation.getIdentityRevision(),
                                    confirmation.getIdentityRevision(),
                                    parent.getIdentityRevision(),
                                    child.getIdentityRevision(),
                                    intent.getIdentityRevision(),
                                    declaration.getIdentityRevision());
                    assertThat(revisions)
                            .as("six actual mapped aggregate revisions")
                            .hasSize(6)
                            .allSatisfy(revision -> assertThat(revision).isEqualTo(expected));
                    System.out.println(
                            "TASK66_SIX_AGGREGATE_HYDRATION revision=" + expected + " count=6");
                    return null;
                });
    }

    @Test
    void identityIsNotPurchaseAcceptance() {
        var registration = registerGuest();
        var quote =
                prepare(
                        counterRequest(
                                "identity",
                                new CounterCustomerRef(
                                        "GUEST",
                                        null,
                                        registration.guestNo(),
                                        registration.claimToken())));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.placeCounter(
                                                        scope,
                                                        new PlaceCounterReq(
                                                                quote.quote().operation(),
                                                                "missing-acceptance",
                                                                "without-accept-" + sku))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED));
        noOrderOrStock(quote.quote().operation());
    }

    @Test
    void counterCannotUseConsumerConfirmation() {
        var quote =
                prepare(
                        counterRequest(
                                "c-denied", new CounterCustomerRef("USER", member, null, null)));
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.confirm(
                                                        new ConfirmCheckoutReq(
                                                                quote.quote().operation(),
                                                                quote.quote().quoteNo(),
                                                                quote.quote().quoteRevision(),
                                                                quote.quote().quoteHash(),
                                                                "c-accept-" + sku))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isIn(ErrorCode.NOT_FOUND, ErrorCode.FORBIDDEN));
        var accepted = accept(quote);
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.place(
                                                        new PlaceCheckoutReq(
                                                                accepted.operation(),
                                                                accepted.confirmationNo(),
                                                                "c-place-" + sku))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isIn(ErrorCode.NOT_FOUND, ErrorCode.FORBIDDEN));
        noOrderOrStock(quote.quote().operation());
    }

    @Test
    void changedQuoteCannotCreate() {
        var quote =
                prepare(
                        counterRequest(
                                "stale", new CounterCustomerRef("USER", member, null, null)));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.acceptCounter(
                                                        scope,
                                                        new AcceptCounterReq(
                                                                quote.quote().operation(),
                                                                "bad-accept-" + sku,
                                                                quote.quote().quoteNo(),
                                                                quote.quote().quoteRevision(),
                                                                "b".repeat(64),
                                                                quote.party().identityRevision(),
                                                                testClock
                                                                        .instant()
                                                                        .truncatedTo(
                                                                                ChronoUnit.MILLIS),
                                                                "MERCHANT_RECORDED_IN_PERSON"))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_QUOTE_STALE));
        noOrderOrStock(quote.quote().operation());
        var accepted = accept(quote);
        jdbc.update("UPDATE prd_sku SET price=price+1 WHERE sku_no=?", sku);
        assertThat(place(accepted).phase()).isEqualTo("REQUOTE_REQUIRED");
        noOrderOrStock(quote.quote().operation());
    }

    @Test
    void sameCounterKeyCreatesOneOriginal() throws Exception {
        var request =
                counterRequest("same-key", new CounterCustomerRef("USER", member, null, null));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first =
                    workers.submit(
                            () -> {
                                start.await();
                                return prepare(request);
                            });
            var second =
                    workers.submit(
                            () -> {
                                start.await();
                                return prepare(request);
                            });
            start.countDown();
            var quote = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS).quote().quoteNo())
                    .isEqualTo(quote.quote().quoteNo());
            var accepted = accept(quote);
            var placeStart = new CountDownLatch(1);
            var one =
                    workers.submit(
                            () -> {
                                placeStart.await();
                                return place(accepted);
                            });
            var two =
                    workers.submit(
                            () -> {
                                placeStart.await();
                                return place(accepted);
                            });
            placeStart.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS).phase()).isIn("CREATED", "PLACING");
            assertThat(two.get(20, TimeUnit.SECONDS).phase()).isIn("CREATED", "PLACING");
            var created = place(accepted);
            assertThat(created.phase()).isEqualTo("CREATED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_create_intent WHERE operation_no=?",
                                    Integer.class,
                                    request.operation().operationNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_order WHERE order_no=?",
                                    Integer.class,
                                    created.orderNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM prd_stock_lock WHERE lock_no=?",
                                    Integer.class,
                                    created.orderNo()))
                    .isEqualTo(1);
        }
    }

    @Test
    void legacyNamespaceBackfillPreservesHistoricalBytesAndOldUniqueness() throws Exception {
        var operation =
                jdbc.queryForMap(
                        "SELECT * FROM ord_checkout_operation"
                                + " WHERE operation_no='T66_LEGACY_OPERATION'");
        assertThat(operation)
                .containsEntry("request_key_hash", "1".repeat(64))
                .containsEntry("payload_hash", "2".repeat(64))
                .containsEntry("place_key_hash", "3".repeat(64))
                .containsEntry("place_payload_hash", "4".repeat(64))
                .containsEntry("scope_hash", "5".repeat(64))
                .containsEntry("customer_kind", "USER")
                .containsEntry("guest_no", null)
                .containsEntry("purchase_authority", "CONSUMER_SESSION")
                .containsEntry("identity_revision", 1L)
                .containsEntry("command_json", "{\"fixture\":\"pre-V542 historical bytes\"}");
        String namespace =
                legacyNamespace(
                        "MAIN",
                        "BEAUTY",
                        "T66_LEGACY_ENTITY",
                        "T66_LEGACY_STORE",
                        "MERCHANT",
                        "MCH",
                        "T66_LEGACY_ACTOR");
        assertThat(operation.get("authority_namespace_hash")).isEqualTo(namespace);
        var confirmation =
                jdbc.queryForMap(
                        "SELECT * FROM ord_checkout_confirmation"
                                + " WHERE confirmation_no='T66_LEGACY_CONFIRMATION'");
        assertThat(confirmation)
                .containsEntry("authority_namespace_hash", namespace)
                .containsEntry("request_key_hash", "7".repeat(64))
                .containsEntry("request_hash", "8".repeat(64))
                .containsEntry("quote_hash", "6".repeat(64))
                .containsEntry("purchase_authority", "CONSUMER_SESSION")
                .containsEntry("customer_kind", "USER")
                .containsEntry("identity_revision", 1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT authority_namespace_hash FROM ord_checkout_confirmation"
                                        + " WHERE confirmation_no='T66_LEGACY_ORPHAN_CONFIRMATION'",
                                String.class))
                .isEqualTo(
                        legacyNamespace(
                                "MAIN",
                                "BEAUTY",
                                "T66_LEGACY_ORPHAN_OPERATION",
                                "T66_LEGACY_USER"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pricing_json FROM ord_checkout_quote WHERE quote_no='T66_LEGACY_QUOTE'",
                                String.class))
                .isEqualTo(
                        "{\"fixture\":\"pre-V542 historical economics bytes; not a Pay receipt\"}");
        rejectNamespaceClone(
                "ord_checkout_confirmation",
                "confirmation_no",
                "T66_LEGACY_CONFIRMATION",
                Map.of(
                        "confirmation_no",
                        "T66_OLD_KEY_CLONE",
                        "operation_no",
                        "T66_OLD_KEY_OPERATION",
                        "quote_no",
                        "T66_OLD_KEY_QUOTE",
                        "authority_namespace_hash",
                        "c".repeat(64)),
                "uk_checkout_confirm_key");
        rejectNamespaceClone(
                "ord_checkout_operation",
                "operation_no",
                "T66_LEGACY_OPERATION",
                Map.of(
                        "operation_no",
                        "T66_OLD_PLACE_CLONE",
                        "request_key_hash",
                        "d".repeat(64),
                        "authority_namespace_hash",
                        "e".repeat(64)),
                "uk_checkout_place_key");
    }

    private static String legacyNamespace(String... fields) {
        var value = new StringBuilder("LEGACY_CHECKOUT_NAMESPACE_V1");
        for (String field : fields) {
            value.append(field.length()).append(':').append(field);
        }
        return StockDispositionContract.hash(value.toString());
    }

    @Test
    void guestConstraintsAndCounterKeysRejectMalformedRows() throws Exception {
        var guest = registerGuest();
        var quote =
                prepare(
                        counterRequest(
                                "constraints",
                                new CounterCustomerRef(
                                        "GUEST", null, guest.guestNo(), guest.claimToken())));
        var accepted = accept(quote);
        var created = place(accepted);
        assertThat(created.phase()).isEqualTo("CREATED");
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        created.orderNo());
        asMerchant(
                () ->
                        manual.confirm(
                                scope,
                                new ConfirmManualReceipt(
                                        quote.quote().operation(),
                                        "receipt-constraints-" + sku,
                                        revision,
                                        ManualMethod.CASH,
                                        quote.quote().dueMinor(),
                                        "CNY",
                                        testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                        null)));
        var rows =
                Map.of(
                        "ord_order", Map.entry("order_no", created.orderNo()),
                        "ord_sub_order", Map.entry("sub_order_no", created.subOrderNo()),
                        "ord_create_intent", Map.entry("pay_no", created.orderNo()),
                        "ord_checkout_operation",
                                Map.entry("operation_no", quote.quote().operation().operationNo()),
                        "ord_checkout_confirmation",
                                Map.entry("confirmation_no", accepted.confirmationNo()),
                        "ord_manual_service_admission", Map.entry("order_no", created.orderNo()));
        for (var row : rows.entrySet()) {
            long id =
                    jdbc.queryForObject(
                            "SELECT id FROM "
                                    + row.getKey()
                                    + " WHERE "
                                    + row.getValue().getKey()
                                    + "=?",
                            Long.class,
                            row.getValue().getValue());
            rejectPartyMutation(row.getKey(), id, "caller=NULL");
            rejectPartyMutation(row.getKey(), id, "entity_no=NULL");
            rejectPartyMutation(row.getKey(), id, "store_no=NULL");
            rejectPartyMutation(row.getKey(), id, "guest_no=NULL");
            rejectPartyMutation(row.getKey(), id, "identity_revision=0");
            String buyerColumn =
                    row.getKey().equals("ord_order")
                                    || row.getKey().equals("ord_sub_order")
                                    || row.getKey().equals("ord_create_intent")
                            ? "user_no"
                            : "buyer_no";
            rejectPartyMutation(row.getKey(), id, buyerColumn + "='fabricated-user'");
        }
        rejectNamespaceClone(
                "ord_checkout_operation",
                "operation_no",
                quote.quote().operation().operationNo(),
                Map.of(
                        "operation_no",
                        "duplicate-op-" + sku,
                        "request_key_hash",
                        CheckoutDigests.hash("dup-" + sku),
                        "order_no",
                        "duplicate-parent-" + sku,
                        "sub_order_no",
                        "duplicate-child-" + sku),
                "uk_counter_place_namespace");
        rejectNamespaceClone(
                "ord_checkout_confirmation",
                "confirmation_no",
                accepted.confirmationNo(),
                Map.of(
                        "confirmation_no",
                        "duplicate-confirm-" + sku,
                        "operation_no",
                        "duplicate-op-" + sku,
                        "quote_no",
                        "duplicate-quote-" + sku,
                        "order_no",
                        "duplicate-parent-" + sku),
                "uk_counter_accept_namespace");
    }

    private void noOrderOrStock(OperationRef operation) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE operation_no=?",
                                Integer.class,
                                operation.operationNo()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_create_intent WHERE operation_no=?",
                                Integer.class,
                                operation.operationNo()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prd_stock_lock WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
    }

    private void rejectPartyMutation(String table, long id, String assignments) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "UPDATE " + table + " SET " + assignments + " WHERE id=?")) {
                statement.setLong(1, id);
                assertThatThrownBy(statement::executeUpdate)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                failure -> assertThat(failure.getErrorCode()).isIn(1048, 3819));
            } finally {
                connection.rollback();
            }
        }
    }

    private void rejectNamespaceClone(
            String table,
            String key,
            String value,
            Map<String, String> replacements,
            String constraint)
            throws Exception {
        var columns =
                jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME<>'id'"
                                + " ORDER BY ORDINAL_POSITION",
                        String.class,
                        table);
        var columnSql = columns.stream().map(column -> '`' + column + '`').toList();
        var selectSql =
                columns.stream()
                        .map(column -> replacements.containsKey(column) ? "?" : '`' + column + '`')
                        .toList();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "INSERT INTO "
                                    + table
                                    + " ("
                                    + String.join(",", columnSql)
                                    + ") SELECT "
                                    + String.join(",", selectSql)
                                    + " FROM "
                                    + table
                                    + " WHERE "
                                    + key
                                    + "=?")) {
                int parameter = 1;
                for (var column : columns) {
                    if (replacements.containsKey(column)) {
                        statement.setString(parameter++, replacements.get(column));
                    }
                }
                statement.setString(parameter, value);
                assertThatThrownBy(statement::executeUpdate)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                failure -> assertThat(failure.getErrorCode()).isEqualTo(1062))
                        .hasMessageContaining(constraint);
            } finally {
                connection.rollback();
            }
        }
    }

    protected static void origin(Object bean, Class<?> expected) {
        var actual = AopUtils.getTargetClass(bean);
        assertThat(actual).isEqualTo(expected);
        String location = actual.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(location).contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
        System.out.println("TASK66_CODE_SOURCE " + actual.getName() + ' ' + location);
    }
}
