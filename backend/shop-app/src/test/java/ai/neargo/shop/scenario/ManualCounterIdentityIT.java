package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl;
import ai.neargo.shop.portal.internal.CounterCustomerOwnerBridge;
import ai.neargo.shop.portal.internal.InternalCustomerIdentityController;
import ai.neargo.shop.portal.internal.InternalGuestRegistrationController;
import ai.neargo.shop.portal.internal.InternalManualServiceAdmissionController;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.order.service.create.CheckoutPricingScope;
import ai.neargo.shop.trade.service.CheckoutOrderWriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real guest claim snapshots remain identification facts without synthesized membership or consent.
 */
@TestPropertySource(
        properties = "shop.services.internal-token=task66-nonsecret-owner-bridge-fixture")
class ManualCounterIdentityIT extends ManualCounterPurchaseIT {
    private static final String BRIDGE_TOKEN = "task66-nonsecret-owner-bridge-fixture";
    @Autowired MerchantOperatorAccessPort bridgeOperators;
    @Autowired InternalManualServiceAdmissionController counterController;
    @MockitoSpyBean InternalCustomerIdentityController customerController;
    @MockitoSpyBean ManualServiceAdmissionPort boundManual;
    @Autowired InternalGuestRegistrationController guestController;
    @MockitoSpyBean CounterCustomerOwnerBridge ownerBridge;
    @MockitoSpyBean CheckoutOrderWriter bridgeWriter;
    @Autowired CheckoutMappers.OperationMapper bridgeOperations;

    @Test
    void counterReadRequiresExactProofOrCurrentSession() {
        origin(bridgeOperators, MerchantOperatorAccessPortImpl.class);
        origin(counterController, InternalManualServiceAdmissionController.class);
        var guest = registerGuest();
        var selector = new CounterCustomerRef("GUEST", null, guest.guestNo(), guest.claimToken());
        var originalRequest = counterRequest("read-proof-original", selector);
        var original = asMerchant(() -> manual.prepareCounter(scope, originalRequest));
        var alternate =
                asMerchant(
                        () ->
                                manual.prepareCounter(
                                        scope, counterRequest("read-proof-alternate", selector)));
        var originalRow =
                jdbc.queryForMap(
                        "SELECT * FROM ord_checkout_operation WHERE operation_no=?",
                        original.quote().operation().operationNo());
        var quoteRow =
                jdbc.queryForMap(
                        "SELECT * FROM ord_checkout_quote WHERE quote_no=?",
                        original.quote().quoteNo());
        String reader = "read-only-" + sku;
        String role = "READ_COUNTER";
        String readPermissions =
                "[\"" + BizPerms.CHECKOUT_PREPARE + "\",\"" + BizPerms.ORDER_VIEW + "\"]";
        jdbc.update(
                "INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                        + " VALUES (?,?,?,0,'ACTIVE',NOW(),NOW())",
                entity,
                reader,
                reader + "-user");
        jdbc.update(
                "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                        + " VALUES (?,?,'counter reader',?,NOW(),NOW())",
                entity,
                role,
                readPermissions);
        jdbc.update(
                "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES (?,?,?,NOW(),NOW())",
                reader,
                store,
                role);
        var readerScope = new CheckoutScope("MAIN", entity, store, reader);
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", reader);
        for (String permission : List.of(BizPerms.CHECKOUT_PREPARE, BizPerms.ORDER_VIEW)) {
            assertThat(
                            bridgeOperators.allowed(
                                    new MerchantOperatorAccessPort.Query(
                                            "MAIN",
                                            entity,
                                            store,
                                            reader,
                                            "MERCHANT",
                                            "MCH",
                                            permission)))
                    .isTrue();
        }
        for (String permission : List.of(BizPerms.CASH_RECEIVE, BizPerms.MANUAL_CONFIRM)) {
            assertThat(
                            bridgeOperators.allowed(
                                    new MerchantOperatorAccessPort.Query(
                                            "MAIN",
                                            entity,
                                            store,
                                            reader,
                                            "MERCHANT",
                                            "MCH",
                                            permission)))
                    .isFalse();
        }
        var local =
                ManualServiceAdmissionIT.currentCashier(
                        tokens,
                        reader,
                        () -> {
                            assertThat(SecurityUtils.currentToken()).isPresent();
                            return manual.readCounterQuote(
                                    readerScope, original.quote().operation());
                        });
        assertThat(local.quote().quoteNo()).isEqualTo(original.quote().quoteNo());
        assertThat(local.party()).isEqualTo(original.party());
        var proof =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                "COUNTER_READ",
                                identity,
                                entity,
                                store,
                                original.quote().operation().operationNo()),
                        new Scoped<>(readerScope, original.quote().operation()));
        var response = counterController.readCounterQuote(proof, BRIDGE_TOKEN);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var reply = (InternalReply<?>) response.getBody();
        new InternalBindings(BRIDGE_TOKEN).require(proof, reply);
        assertThat(((CounterCheckoutQuote) reply.result()).quote().quoteNo())
                .isEqualTo(original.quote().quoteNo());
        assertThat(SecurityUtils.currentToken()).isEmpty();
        assertThat(BoundActionContext.present()).isFalse();
        System.out.println(
                "TASK66_READ_PROOF actualReadOnlyLocal/exactBound/differentCashier PASS");

        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        identity,
                                        () ->
                                                manual.readCounterQuote(
                                                        readerScope, original.quote().operation())))
                .as(
                        "raw delegation without verified action or current token must not read a quote")
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        var preparePurpose =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                "COUNTER_PREPARE",
                                identity,
                                entity,
                                store,
                                original.quote().operation().operationNo()),
                        new Scoped<>(readerScope, originalRequest));
        assertThat(
                        readUnderProof(preparePurpose, readerScope, original.quote().operation())
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        readUnderProof(proof, readerScope, alternate.quote().operation())
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        readUnderProof(proof, scope, original.quote().operation())
                                .getStatusCode()
                                .value())
                .isIn(400, 403);
        jdbc.update(
                "UPDATE mch_role SET perms=? WHERE entity_no=? AND role_code=?",
                "[\"" + BizPerms.CHECKOUT_PREPARE + "\"]",
                entity,
                role);
        try {
            assertThatThrownBy(
                            () ->
                                    ManualServiceAdmissionIT.currentCashier(
                                            tokens,
                                            reader,
                                            () ->
                                                    manual.readCounterQuote(
                                                            readerScope,
                                                            original.quote().operation())))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            failure ->
                                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(
                            readUnderProof(proof, readerScope, original.quote().operation())
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(403);
        } finally {
            jdbc.update(
                    "UPDATE mch_role SET perms=? WHERE entity_no=? AND role_code=?",
                    readPermissions,
                    entity,
                    role);
        }
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM ord_checkout_operation WHERE operation_no=?",
                                original.quote().operation().operationNo()))
                .isEqualTo(originalRow);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM ord_checkout_quote WHERE quote_no=?",
                                original.quote().quoteNo()))
                .isEqualTo(quoteRow);
        assertThat(BoundActionContext.present()).isFalse();
        assertThat(SecurityUtils.currentUser()).isEmpty();
        assertThat(SecurityUtils.currentToken()).isEmpty();
        System.out.println(
                "TASK66_READ_PROOF rawDelegation/purpose/operation/scope/revokedReadGrant/unchangedOriginal PASS");
    }

    private ResponseEntity<?> readUnderProof(
            InternalCall<?> proof, CheckoutScope actualScope, OperationRef operation) {
        return new BoundDelegatedCalls(BRIDGE_TOKEN)
                .invoke(
                        BRIDGE_TOKEN,
                        proof,
                        proof.binding().endpoint(),
                        proof.binding().action(),
                        BoundDelegatedCalls.Mode.MERCHANT,
                        proof.binding().targetNo(),
                        () -> manual.readCounterQuote(actualScope, operation));
    }

    @Test
    void boundUserAndClaimedGuestUseActualMemberOwners() {
        origin(bridgeOperators, MerchantOperatorAccessPortImpl.class);
        origin(counterController, InternalManualServiceAdmissionController.class);
        origin(customerController, InternalCustomerIdentityController.class);
        origin(guestController, InternalGuestRegistrationController.class);
        origin(ownerBridge, CounterCustomerOwnerBridge.class);
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor);
        var bindings = new InternalBindings(BRIDGE_TOKEN);
        var registered = registerGuest();
        String claimedUser = "bound-claimed-" + sku;
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                        + " VALUES (?,'bound claim actual account','NORMAL',NOW(),NOW())",
                claimedUser);
        String code = identities.issue("MAIN", claimedUser).memberCode();
        String claimedReference =
                asMerchant(
                        () ->
                                identities
                                        .resolve(
                                                new MemberCodeScope("MAIN", entity, store, actor),
                                                code)
                                        .customerRef());
        var claimed =
                asMerchant(
                        () ->
                                guests.claim(
                                        guestScope(),
                                        registered.guestNo(),
                                        new ClaimCommand(
                                                claimedReference,
                                                registered.claimToken(),
                                                "bridge-claim-" + sku)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM usr_person WHERE user_no=?",
                                Long.class,
                                claimedUser))
                .isZero();
        assertThat(SecurityUtils.currentToken()).isEmpty();
        var context =
                new EntitlementContext(
                        "MAIN",
                        actor,
                        "bridge-member-preflight-" + sku,
                        CheckoutDigests.hash("bridge-member-preflight-" + sku));
        var memberRequest =
                new CustomerIdentityInternalApi.CounterReferenceRequest(
                        identity,
                        context,
                        new CustomerIdentityPort.CounterReferenceQuery(entity, store, member));
        var memberCall =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                CustomerIdentityInternalApi.COUNTER_ENDPOINT,
                                "RESOLVE",
                                identity,
                                entity,
                                store,
                                member),
                        memberRequest);
        var memberResponse = customerController.resolveCounterReference(memberCall, BRIDGE_TOKEN);
        assertThat(memberResponse.getStatusCode().is2xxSuccessful()).isTrue();
        var memberReply = (InternalReply<?>) memberResponse.getBody();
        bindings.require(memberCall, memberReply);
        assertThat(
                        ((CustomerIdentityInternalApi.CounterReferenceResolution)
                                        memberReply.result())
                                .customer()
                                .userNo())
                .isEqualTo(buyer);
        var guestRequest =
                new GuestRegistrationInternalApi.Verify(
                        guestScope(), registered.guestNo(), registered.claimToken());
        var guestCall =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                GuestRegistrationInternalApi.COUNTER_CUSTOMER_ENDPOINT,
                                "RESOLVE",
                                identity,
                                entity,
                                store,
                                registered.guestNo()),
                        guestRequest);
        var guestResponse = guestController.resolveClaimedCustomer(guestCall, BRIDGE_TOKEN);
        assertThat(guestResponse.getStatusCode().is2xxSuccessful()).isTrue();
        var guestReply = (InternalReply<?>) guestResponse.getBody();
        bindings.require(guestCall, guestReply);
        var actual =
                ((GuestRegistrationInternalApi.ClaimedCustomerResolution) guestReply.result())
                        .customer();
        assertThat(actual.customer().userNo()).isEqualTo(claimedUser);
        assertThat(actual.guest().revision()).isEqualTo(claimed.revision());
        assertThat(BoundActionContext.present()).isFalse();
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            var pricing = (CheckoutPricingScope) call.getArgument(2);
                            var raw =
                                    jdbc.queryForMap(
                                            "SELECT identity_revision FROM ord_checkout_operation"
                                                    + " WHERE caller='BEAUTY' AND operation_no=?",
                                            pricing.operationNo());
                            var mapped = bridgeOperations.lock("BEAUTY", pricing.operationNo());
                            assertThat(raw.get("identity_revision")).isNull();
                            assertThat(mapped.getIdentityRevision())
                                    .as("actual mapped counter USER revision stays NULL")
                                    .isNull();
                            assertThat(pricing.party().identityRevision()).isNull();
                            System.out.println(
                                    "TASK66_COUNTER_NULL_DIAGNOSTIC rawRevisionNull="
                                            + (raw.get("identity_revision") == null)
                                            + " mappedRevision="
                                            + mapped.getIdentityRevision()
                                            + " requestedRevision="
                                            + pricing.party().identityRevision());
                            return call.callRealMethod();
                        })
                .when(bridgeWriter)
                .quoteManualFor(any(), any(), any());
        doAnswer(
                        call -> {
                            try {
                                return call.callRealMethod();
                            } catch (BizException failure) {
                                System.out.println(
                                        "TASK66_BRIDGE_BUSINESS_ERROR code="
                                                + failure.errorCode().name());
                                var frames = failure.getStackTrace();
                                for (int frame = 0; frame < Math.min(8, frames.length); frame++) {
                                    System.out.println(
                                            "TASK66_BRIDGE_SAFE_FRAME "
                                                    + frames[frame].getClassName()
                                                    + '.'
                                                    + frames[frame].getMethodName()
                                                    + ':'
                                                    + frames[frame].getLineNumber());
                                }
                                throw failure;
                            }
                        })
                .when(boundManual)
                .prepareCounter(any(), any());
        int invocation = 0;
        for (var selector :
                List.of(
                        new CounterCustomerRef("USER", member, null, null),
                        new CounterCustomerRef(
                                "CLAIMED_GUEST",
                                null,
                                registered.guestNo(),
                                registered.claimToken()))) {
            var request = counterRequest("bound-" + selector.kind(), selector);
            var call =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    ManualServiceAdmissionInternalApi.ENDPOINT,
                                    "COUNTER_PREPARE",
                                    identity,
                                    entity,
                                    store,
                                    request.operation().operationNo()),
                            new Scoped<>(scope, request));
            var response = counterController.prepareCounter(call, BRIDGE_TOKEN);
            verify(ownerBridge, times(++invocation)).resolveBound(any(), any());
            assertThat(SecurityUtils.currentToken()).isEmpty();
            assertThat(BoundActionContext.present()).isFalse();
            System.out.println(
                    "TASK66_BRIDGE_BOUNDARY selector="
                            + selector.kind()
                            + " actualResolverInvocations="
                            + invocation
                            + " status="
                            + response.getStatusCode().value()
                            + " errorCode="
                            + response.getHeaders().getFirst("X-Internal-Error-Code"));
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as(
                            "actual bound counter bridge must resolve "
                                    + selector.kind()
                                    + " through member owners")
                    .isTrue();
            var reply = (InternalReply<?>) response.getBody();
            bindings.require(call, reply);
            var quote = (CounterCheckoutQuote) reply.result();
            assertThat(quote.party().kind()).isEqualTo("USER");
            assertThat(quote.party().userNo())
                    .isEqualTo("USER".equals(selector.kind()) ? buyer : claimedUser);
            assertThat(quote.party().identityRevision()).isNull();
            if ("CLAIMED_GUEST".equals(selector.kind())) {
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT JSON_EXTRACT(command_json,'$.selector.claimRevision')"
                                                + " FROM ord_checkout_operation WHERE operation_no=?",
                                        Long.class,
                                        request.operation().operationNo()))
                        .isEqualTo(claimed.revision());
            }
        }
        hostileBridgeRequests(
                identity,
                memberCall,
                new CounterCustomerRef(
                        "CLAIMED_GUEST", null, registered.guestNo(), registered.claimToken()));
    }

    private void hostileBridgeRequests(
            DelegatedIdentity identity,
            InternalCall<CustomerIdentityInternalApi.CounterReferenceRequest> memberCall,
            CounterCustomerRef claimedSelector) {
        var request =
                counterRequest(
                        "hostile-original", new CounterCustomerRef("USER", member, null, null));
        var original = outer(identity, request);
        var changedRemark =
                altered(request, request.operation(), request.customer(), "changed private remark");
        var changedOperation =
                altered(
                        request,
                        new OperationRef("BEAUTY", request.operation().operationNo() + "-x"),
                        request.customer(),
                        request.remark());
        var changedCustomer =
                altered(
                        request,
                        request.operation(),
                        new CounterCustomerRef("USER", buyer, null, null),
                        request.remark());
        var changedProof =
                altered(
                        request,
                        request.operation(),
                        new CounterCustomerRef(
                                "CLAIMED_GUEST", null, claimedSelector.guestNo(), "z".repeat(43)),
                        request.remark());
        // The authenticated outer controller stays real; these spies model a corrupted composition
        // handoff.
        for (var attempt :
                List.of(
                        new BridgeAttempt(scope, changedRemark),
                        new BridgeAttempt(scope, changedOperation),
                        new BridgeAttempt(scope, changedCustomer),
                        new BridgeAttempt(scope, changedProof),
                        new BridgeAttempt(
                                new CheckoutScope("MAIN", entity, store + "-x", actor), request),
                        new BridgeAttempt(
                                new CheckoutScope("MAIN", entity + "-x", store, actor), request),
                        new BridgeAttempt(
                                new CheckoutScope("MAIN", entity, store, buyer), request))) {
            clearInvocations(customers, identityGuests);
            doAnswer(
                            call -> {
                                ownerBridge.resolveBound(attempt.scope(), attempt.request());
                                throw new AssertionError(
                                        "changed outer payload must not derive a member purpose");
                            })
                    .when(boundManual)
                    .prepareCounter(any(), any());
            try {
                assertThat(
                                counterController
                                        .prepareCounter(original, BRIDGE_TOKEN)
                                        .getStatusCode()
                                        .value())
                        .isIn(400, 403);
                verify(customers, never()).resolveCounterReference(any(), any());
                verify(identityGuests, never())
                        .resolveClaimedCustomer(any(), anyString(), anyString());
                assertThat(BoundActionContext.present()).isFalse();
                assertThat(SecurityUtils.currentToken()).isEmpty();
            } finally {
                reset(boundManual);
            }
        }
        assertThatThrownBy(() -> asMerchant(() -> ownerBridge.resolveBound(scope, request)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(
                        () ->
                                DelegatedIdentityContext.call(
                                        identity, () -> ownerBridge.resolveBound(scope, request)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        clearInvocations(ownerBridge);
        var wrongPurpose =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                "COUNTER_READ",
                                identity,
                                entity,
                                store,
                                request.operation().operationNo()),
                        original.request());
        assertThat(
                        counterController
                                .prepareCounter(wrongPurpose, BRIDGE_TOKEN)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        counterController
                                .prepareCounter(original, "wrong-token")
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        verify(ownerBridge, never()).resolveBound(any(), any());
        // A legitimate inner member purpose cannot recursively mint an outer counter capability.
        doAnswer(
                        call -> {
                            ownerBridge.resolveBound(scope, request);
                            throw new AssertionError(
                                    "inner member purpose must not authorize counter composition");
                        })
                .when(customers)
                .resolveCounterReference(any(), any());
        try {
            assertThat(
                            customerController
                                    .resolveCounterReference(memberCall, BRIDGE_TOKEN)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(400);
            assertThat(BoundActionContext.present()).isFalse();
        } finally {
            reset(customers);
        }
        // Even a genuine owner result is rejected if its full echoed selector was substituted.
        doAnswer(
                        call -> {
                            var actual = (ResponseEntity<?>) call.callRealMethod();
                            var reply = (InternalReply<?>) actual.getBody();
                            var resolution =
                                    (CustomerIdentityInternalApi.CounterReferenceResolution)
                                            reply.result();
                            var substituted =
                                    new CustomerIdentityInternalApi.CounterReferenceRequest(
                                            identity,
                                            resolution.request().context(),
                                            new CustomerIdentityPort.CounterReferenceQuery(
                                                    entity, store, buyer));
                            return ResponseEntity.ok(
                                    new InternalReply<>(
                                            reply.binding(),
                                            reply.requestDigest(),
                                            new CustomerIdentityInternalApi
                                                    .CounterReferenceResolution(
                                                    substituted, resolution.customer())));
                        })
                .when(customerController)
                .resolveCounterReference(any(), anyString());
        try {
            assertThat(
                            counterController
                                    .prepareCounter(original, BRIDGE_TOKEN)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(503);
            assertThat(BoundActionContext.present()).isFalse();
        } finally {
            reset(customerController);
        }
        var revoked =
                counterRequest("bound-revoked", new CounterCustomerRef("USER", member, null, null));
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
        try {
            assertThat(
                            counterController
                                    .prepareCounter(outer(identity, revoked), BRIDGE_TOKEN)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(403);
            assertThat(BoundActionContext.present()).isFalse();
        } finally {
            jdbc.update("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no=?", actor);
            reset(customers);
        }
        for (var absent : List.of(request.operation(), revoked.operation())) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_operation WHERE operation_no=?",
                                    Long.class,
                                    absent.operationNo()))
                    .isZero();
        }
        System.out.println(
                "TASK66_BRIDGE_HOSTILE fullPayload7/unbound2/route/token/"
                        + "nestedPurpose/innerEcho/revoked PASS");
    }

    private InternalCall<Scoped<PrepareCounterReq>> outer(
            DelegatedIdentity identity, PrepareCounterReq request) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        ManualServiceAdmissionInternalApi.ENDPOINT,
                        "COUNTER_PREPARE",
                        identity,
                        entity,
                        store,
                        request.operation().operationNo()),
                new Scoped<>(scope, request));
    }

    private PrepareCounterReq altered(
            PrepareCounterReq original,
            OperationRef operation,
            CounterCustomerRef selector,
            String remark) {
        return new PrepareCounterReq(
                operation,
                original.requestKey(),
                selector,
                original.scopeRevision(),
                original.scopeHash(),
                original.lines(),
                original.couponNo(),
                original.activityChoices(),
                remark);
    }

    private record BridgeAttempt(CheckoutScope scope, PrepareCounterReq request) {}

    @MockitoSpyBean GuestRegistrationPort identityGuests;

    @Test
    void newPrepareAfterClaimRequiresCanonicalUser() {
        var registration = registerGuest();
        var guestScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        asMerchant(
                () ->
                        guests.claim(
                                guestScope,
                                registration.guestNo(),
                                new ClaimCommand(
                                        reference,
                                        registration.claimToken(),
                                        "canonical-claim-" + sku)));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.prepareCounter(
                                                        scope,
                                                        counterRequest(
                                                                "claimed-denied",
                                                                new CounterCustomerRef(
                                                                        "GUEST",
                                                                        null,
                                                                        registration.guestNo(),
                                                                        registration
                                                                                .claimToken())))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        var actual =
                prepare(
                        counterRequest(
                                "canonical-after-claim",
                                new CounterCustomerRef("USER", member, null, null)));
        assertThat(actual.party().userNo()).isEqualTo(buyer);
        assertThat(actual.party().identityRevision()).isNull();
        assertThat(place(accept(actual)).phase()).isEqualTo("CREATED");
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.prepareCounter(
                                                        scope,
                                                        counterRequest(
                                                                "arbitrary-user",
                                                                new CounterCustomerRef(
                                                                        "USER", buyer, null,
                                                                        null)))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void claimBetweenQuoteAndAcceptanceKeepsGuestSnapshot() throws Exception {
        var registered = registerGuest();
        var observed = new CountDownLatch(1);
        var continuePrepare = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        doAnswer(
                        call -> {
                            var fact = (GuestRegistrationPort.GuestFact) call.callRealMethod();
                            if (once.getAndSet(false)) {
                                assertThat(fact.state()).isEqualTo("REGISTERED");
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                observed.countDown();
                                assertThat(continuePrepare.await(5, TimeUnit.SECONDS)).isTrue();
                            }
                            return fact;
                        })
                .when(identityGuests)
                .verify(any(), anyString(), anyString());
        var guestScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        try (var workers = Executors.newFixedThreadPool(1)) {
            var preparing =
                    workers.submit(
                            () ->
                                    prepare(
                                            counterRequest(
                                                    "claim-observation",
                                                    new CounterCustomerRef(
                                                            "GUEST",
                                                            null,
                                                            registered.guestNo(),
                                                            registered.claimToken()))));
            assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var claimed =
                        asMerchant(
                                () ->
                                        guests.claim(
                                                guestScope,
                                                registered.guestNo(),
                                                new ClaimCommand(
                                                        reference,
                                                        registered.claimToken(),
                                                        "claim-between-" + sku)));
                assertThat(claimed.revision()).isGreaterThan(registered.revision());
            } finally {
                continuePrepare.countDown();
            }
            var quote = preparing.get(10, TimeUnit.SECONDS);
            assertThat(quote.party().kind()).isEqualTo("GUEST");
            assertThat(quote.party().identityRevision()).isEqualTo(registered.revision());
            var created = place(accept(quote));
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT customer_kind,user_no,guest_no,identity_revision"
                                            + " FROM ord_order WHERE order_no=?",
                                    created.orderNo()))
                    .containsEntry("customer_kind", "GUEST")
                    .containsEntry("user_no", null)
                    .containsEntry("guest_no", registered.guestNo())
                    .containsEntry("identity_revision", registered.revision());
            assertThat(asBuyer(() -> writer.detail(created.orderNo(), null)).orderNo())
                    .isEqualTo(created.orderNo());
        }
    }

    @Test
    void claimAndCounterCreationPreserveOneOriginal() throws Exception {
        var registration = registerGuest();
        var quote =
                prepare(
                        counterRequest(
                                "claim-creation",
                                new CounterCustomerRef(
                                        "GUEST",
                                        null,
                                        registration.guestNo(),
                                        registration.claimToken())));
        var accepted = accept(quote);
        var start = new CountDownLatch(1);
        var guestScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var creating =
                    workers.submit(
                            () -> {
                                start.await();
                                return place(accepted);
                            });
            var claiming =
                    workers.submit(
                            () -> {
                                start.await();
                                return asMerchant(
                                        () ->
                                                guests.claim(
                                                        guestScope,
                                                        registration.guestNo(),
                                                        new ClaimCommand(
                                                                reference,
                                                                registration.claimToken(),
                                                                "creation-claim-" + sku)));
                            });
            start.countDown();
            var created = creating.get(15, TimeUnit.SECONDS);
            assertThat(claiming.get(15, TimeUnit.SECONDS).userNo()).isEqualTo(buyer);
            assertThat(place(accepted).orderNo()).isEqualTo(created.orderNo());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_create_intent WHERE operation_no=?",
                                    Long.class,
                                    accepted.operation().operationNo()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT customer_kind,user_no,guest_no,identity_revision FROM ord_order"
                                            + " WHERE order_no=?",
                                    created.orderNo()))
                    .containsEntry("customer_kind", "GUEST")
                    .containsEntry("user_no", null)
                    .containsEntry("guest_no", registration.guestNo())
                    .containsEntry("identity_revision", registration.revision());
            assertThat(asBuyer(() -> writer.detail(created.orderNo(), null)).orderNo())
                    .isEqualTo(created.orderNo());
        }
    }

    @Test
    void claimedGuestWithoutMembershipCanPrepareUserCounter() {
        var registered = registerGuest();
        var historical =
                prepare(
                        counterRequest(
                                "historical-guest",
                                new CounterCustomerRef(
                                        "GUEST",
                                        null,
                                        registered.guestNo(),
                                        registered.claimToken())));
        String actualUser = "claimed-" + sku;
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                        + " VALUES (?,'claimed actual account','NORMAL',NOW(),NOW())",
                actualUser);
        String code = identities.issue("MAIN", actualUser).memberCode();
        String reference =
                asMerchant(
                        () ->
                                identities
                                        .resolve(
                                                new MemberCodeScope("MAIN", entity, store, actor),
                                                code)
                                        .customerRef());
        var ownerScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        var claimed =
                asMerchant(
                        () ->
                                guests.claim(
                                        ownerScope,
                                        registered.guestNo(),
                                        new ClaimCommand(
                                                reference,
                                                registered.claimToken(),
                                                "claim-" + sku)));
        assertThat(claimed.userNo()).isEqualTo(actualUser);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM usr_person WHERE user_no=?",
                                Long.class,
                                actualUser))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mbr_member m JOIN usr_person p ON p.person_no=m.person_no"
                                        + " WHERE m.entity_no=? AND p.user_no=?",
                                Long.class,
                                entity,
                                actualUser))
                .isZero();
        assertThatThrownBy(
                        () ->
                                prepare(
                                        counterRequest(
                                                "already-claimed",
                                                new CounterCustomerRef(
                                                        "GUEST",
                                                        null,
                                                        registered.guestNo(),
                                                        registered.claimToken()))))
                .hasRootCauseInstanceOf(BizException.class);
        var request =
                counterRequest(
                        "claimed-user",
                        new CounterCustomerRef(
                                "CLAIMED_GUEST",
                                null,
                                registered.guestNo(),
                                registered.claimToken()));
        var quote = prepare(request);
        assertThat(quote.party().kind()).isEqualTo("USER");
        assertThat(quote.party().userNo()).isEqualTo(actualUser);
        assertThat(quote.party().guestNo()).isNull();
        assertThat(quote.party().identityRevision()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT JSON_EXTRACT(command_json,'$.selector.claimRevision')"
                                        + " FROM ord_checkout_operation WHERE operation_no=?",
                                Long.class,
                                quote.quote().operation().operationNo()))
                .isEqualTo(claimed.revision());
        assertThat(prepare(request)).isEqualTo(quote);
        var original =
                asMerchant(() -> manual.readCounterQuote(scope, historical.quote().operation()));
        assertThat(original.party()).isEqualTo(historical.party());
        assertThat(place(accept(quote)).orderNo()).isNotBlank();
        assertThat(place(accept(historical)).orderNo()).isNotBlank();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_kind FROM ord_checkout_operation WHERE operation_no=?",
                                String.class,
                                historical.quote().operation().operationNo()))
                .isEqualTo("GUEST");
    }

    @Test
    void claimedSelectorRejectsWrongTokenRegisteredAndInactiveAccount() {
        var registered = registerGuest();
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.prepareCounter(
                                                        scope,
                                                        counterRequest(
                                                                "not-claimed",
                                                                new CounterCustomerRef(
                                                                        "CLAIMED_GUEST",
                                                                        null,
                                                                        registered.guestNo(),
                                                                        registered.claimToken())))))
                .isInstanceOf(BizException.class);
        var ownerScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        asMerchant(
                () ->
                        guests.claim(
                                ownerScope,
                                registered.guestNo(),
                                new ClaimCommand(
                                        reference,
                                        registered.claimToken(),
                                        "claim-denial-" + sku)));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.prepareCounter(
                                                        scope,
                                                        counterRequest(
                                                                "wrong-token",
                                                                new CounterCustomerRef(
                                                                        "CLAIMED_GUEST",
                                                                        null,
                                                                        registered.guestNo(),
                                                                        "z".repeat(43))))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        jdbc.update("UPDATE usr_account SET status='DISABLED' WHERE user_no=?", buyer);
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.prepareCounter(
                                                        scope,
                                                        counterRequest(
                                                                "inactive-claim",
                                                                new CounterCustomerRef(
                                                                        "CLAIMED_GUEST",
                                                                        null,
                                                                        registered.guestNo(),
                                                                        registered.claimToken())))))
                .isInstanceOf(BizException.class);
    }
}
