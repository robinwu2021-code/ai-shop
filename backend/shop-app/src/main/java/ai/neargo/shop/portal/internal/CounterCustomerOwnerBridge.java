package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort.CounterReferenceQuery;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerResolution;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerResolver;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Trusted HOST composition keeps member purposes separate from counter purchase authority. */
@Component
public class CounterCustomerOwnerBridge implements CounterCustomerResolver {
    private final InternalCustomerIdentityController customers;
    private final InternalGuestRegistrationController guests;
    private final InternalBindings bindings;
    private final String token;
    private final ObjectMapper json;

    /** Reuse the existing token-first controllers and their context restoration lifecycle. */
    public CounterCustomerOwnerBridge(
            InternalCustomerIdentityController customers,
            InternalGuestRegistrationController guests,
            @Value("${shop.services.internal-token:}") String token,
            ObjectMapper json) {
        this.customers = customers;
        this.guests = guests;
        this.token = token;
        this.json = json;
        bindings = new InternalBindings(token);
    }

    @Override
    public CounterCustomerResolution resolveBound(CheckoutScope scope, PrepareCounterReq request) {
        var identity = requireOuter(scope, request);
        CounterCustomerResolution resolved;
        if ("USER".equals(request.customer().kind())) {
            var context =
                    new EntitlementContext(
                            scope.tenantNo(),
                            identity.actorNo(),
                            request.operation().operationNo(),
                            CheckoutDigests.hash(
                                    json,
                                    List.of(
                                            "COUNTER_MEMBER_REFERENCE_V1",
                                            request.customer().memberNo(),
                                            request.scopeHash())));
            var payload =
                    new CustomerIdentityInternalApi.CounterReferenceRequest(
                            identity,
                            context,
                            new CounterReferenceQuery(
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    request.customer().memberNo()));
            var inner =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    CustomerIdentityInternalApi.COUNTER_ENDPOINT,
                                    "RESOLVE",
                                    identity,
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    request.customer().memberNo()),
                            payload);
            var result = result(inner, customers.resolveCounterReference(inner, token));
            if (!(result instanceof CustomerIdentityInternalApi.CounterReferenceResolution actual)
                    || !payload.equals(actual.request())
                    || actual.customer() == null) {
                throw unavailable();
            }
            resolved = user(actual.customer().userNo(), null);
        } else if ("CLAIMED_GUEST".equals(request.customer().kind())) {
            var payload =
                    new GuestRegistrationInternalApi.Verify(
                            new Scope(scope.entityNo(), scope.storeNo(), identity),
                            request.customer().guestNo(),
                            request.customer().claimToken());
            var inner =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    GuestRegistrationInternalApi.COUNTER_CUSTOMER_ENDPOINT,
                                    "RESOLVE",
                                    identity,
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    request.customer().guestNo()),
                            payload);
            var result = result(inner, guests.resolveClaimedCustomer(inner, token));
            if (!(result instanceof GuestRegistrationInternalApi.ClaimedCustomerResolution actual)
                    || !payload.equals(actual.request())
                    || actual.customer() == null
                    || actual.customer().guest() == null
                    || actual.customer().customer() == null) {
                throw unavailable();
            }
            var guest = actual.customer().guest();
            var customer = actual.customer().customer();
            if (!request.customer().guestNo().equals(guest.guestNo())
                    || !"CLAIMED".equals(guest.state())
                    || guest.revision() <= 0
                    || guest.userNo() == null
                    || !guest.userNo().equals(customer.userNo())) {
                throw unavailable();
            }
            resolved = user(customer.userNo(), guest.revision());
        } else {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        // Inner token-first handlers must restore this exact original purpose and current identity.
        if (!identity.equals(requireOuter(scope, request))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return resolved;
    }

    private DelegatedIdentity requireOuter(CheckoutScope scope, PrepareCounterReq request) {
        DelegatedIdentityContext.outside();
        if (!BoundActionContext.present() || scope == null || request == null) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var identity = DelegatedIdentityContext.current(scope.actorNo());
        if (!"MERCHANT".equals(identity.realm()) || !scope.tenantNo().equals(identity.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var outer =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                "COUNTER_PREPARE",
                                identity,
                                scope.entityNo(),
                                scope.storeNo(),
                                request.operation().operationNo()),
                        new Scoped<>(scope, request));
        BoundActionContext.require(outer);
        return identity;
    }

    private Object result(InternalCall<?> request, ResponseEntity<?> response) {
        if (response == null
                || !response.getStatusCode().is2xxSuccessful()
                || !(response.getBody() instanceof InternalReply<?> reply)) {
            throw unavailable();
        }
        try {
            bindings.require(request, reply);
            return reply.result();
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    private CounterCustomerResolution user(String userNo, Long selectorRevision) {
        if (userNo == null || userNo.isBlank()) {
            throw unavailable();
        }
        try {
            return new CounterCustomerResolution(
                    new CustomerParty("USER", userNo, null, (Long) null), selectorRevision);
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    private BizException unavailable() {
        return BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
    }
}
