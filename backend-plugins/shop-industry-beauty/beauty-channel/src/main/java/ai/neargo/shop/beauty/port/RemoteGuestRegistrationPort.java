package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import java.util.HashSet;
import java.util.List;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.CallOutcome;
import java.util.function.Supplier;

/** Only complete owner responses matching the authenticated request become shared guest facts. */
public final class RemoteGuestRegistrationPort implements GuestRegistrationPort {
    private final GuestRegistrationInternalApi api;
    private final InternalBindings bindings;

    public RemoteGuestRegistrationPort(GuestRegistrationInternalApi api) {
        this(api, null);
    }

    /** Bound current-claim reads require the deployment's existing internal binding secret. */
    public RemoteGuestRegistrationPort(
            GuestRegistrationInternalApi api, InternalBindings bindings) {
        this.api = api;
        this.bindings = bindings;
    }

    @Override
    public ClaimedCustomer resolveClaimedCustomer(Scope scope, String guestNo, String claimToken) {
        current(scope);
        var payload = new GuestRegistrationInternalApi.Verify(scope, guestNo, claimToken);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                GuestRegistrationInternalApi.COUNTER_CUSTOMER_ENDPOINT,
                                "RESOLVE",
                                scope.identity(),
                                scope.entityNo(),
                                scope.storeNo(),
                                guestNo),
                        payload);
        try {
            var reply = ServiceCalls.call("PLATFORM", () -> api.resolveClaimedCustomer(call));
            bindings.require(call, reply);
            var result = reply.result();
            require(
                    payload.equals(result.request())
                            && result.customer() != null
                            && guestNo.equals(result.customer().guest().guestNo()));
            current(scope);
            return result.customer();
        } catch (RuntimeException unavailable) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public CurrentClaim currentClaim(ConsumerScope scope, GuestRef guest) {
        consumer(scope);
        var payload = new GuestRegistrationInternalApi.CurrentClaimRequest(scope, guest);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                GuestRegistrationInternalApi.CURRENT_ENDPOINT,
                                "CURRENT_CLAIM",
                                scope.identity(),
                                null,
                                null,
                                guest.guestNo()),
                        payload);
        try {
            var reply = ServiceCalls.call("PLATFORM", () -> api.currentClaim(call));
            bindings.require(call, reply);
            var result = reply.result();
            var claim = result.claim();
            require(
                    payload.equals(result.request())
                            && claim != null
                            && scope.identity().actorNo().equals(claim.userNo())
                            && guest.equals(
                                    new GuestRef(
                                            claim.entityNo(), claim.storeNo(), claim.guestNo())));
            consumer(scope);
            return claim;
        } catch (RuntimeException unavailable) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public List<CurrentClaim> currentClaims(ConsumerScope scope) {
        consumer(scope);
        var payload = new GuestRegistrationInternalApi.CurrentClaimsRequest(scope);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                GuestRegistrationInternalApi.CURRENT_ENDPOINT,
                                "CURRENT_CLAIMS",
                                scope.identity(),
                                null,
                                null,
                                scope.identity().actorNo()),
                        payload);
        try {
            var reply = ServiceCalls.call("PLATFORM", () -> api.currentClaims(call));
            bindings.require(call, reply);
            var result = reply.result();
            require(payload.equals(result.request()));
            var refs = new HashSet<GuestRef>();
            for (var claim : result.claims()) {
                require(
                        scope.identity().actorNo().equals(claim.userNo())
                                && refs.add(
                                        new GuestRef(
                                                claim.entityNo(),
                                                claim.storeNo(),
                                                claim.guestNo())));
            }
            consumer(scope);
            return List.copyOf(result.claims());
        } catch (RuntimeException unavailable) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }

    private void consumer(ConsumerScope scope) {
        require(
                bindings != null
                        && scope != null
                        && scope.identity() != null
                        && "CONSUMER".equals(scope.identity().realm())
                        && "USR".equals(scope.identity().subjectKind())
                        && scope.identity().equals(DelegatedIdentityContext.current()));
    }

    @Override public Registration register(Scope scope, RegisterCommand command) {
        current(scope);
        var request = new GuestRegistrationInternalApi.Register(scope, command);
        var result = write(() -> api.register(request));
        require(result != null && request.equals(result.request()) && result.registration() != null);
        var registration = result.registration();
        require(id(registration.guestNo()) && "REGISTERED".equals(registration.state()) && registration.revision() == 1
                && registration.claimToken() != null && registration.claimToken().matches("[A-Za-z0-9_-]{43}"));
        return registration;
    }
    @Override public GuestFact get(Scope scope, String guestNo) {
        current(scope);
        var request = new GuestRegistrationInternalApi.Get(scope, guestNo);
        var result = ServiceCalls.call("PLATFORM", () -> api.get(request));
        require(result != null && request.equals(result.request()));
        return fact(result.guest(), guestNo);
    }
    @Override public GuestFact verify(Scope scope, String guestNo, String token) {
        current(scope);
        var request = new GuestRegistrationInternalApi.Verify(scope, guestNo, token);
        var result = ServiceCalls.call("PLATFORM", () -> api.verify(request));
        require(result != null && request.equals(result.request()));
        return fact(result.guest(), guestNo);
    }
    @Override public Claim claim(Scope scope, String guestNo, ClaimCommand command) {
        current(scope);
        var request = new GuestRegistrationInternalApi.ClaimRequest(scope, guestNo, command);
        var result = write(() -> api.claim(request));
        require(result != null && request.equals(result.request()) && result.claim() != null);
        var claim = result.claim();
        require(guestNo.equals(claim.guestNo()) && id(claim.userNo()) && claim.revision() == 2);
        return claim;
    }
    private static <T> T write(Supplier<T> invocation) {
        try { return ServiceCalls.call("PLATFORM", invocation); }
        catch (ServiceCallException failure) {
            if (failure.statusCode() == 0 && (failure.outcome() == CallOutcome.TIMEOUT
                    || failure.outcome() == CallOutcome.UNREACHABLE || failure.outcome() == CallOutcome.REMOTE_ERROR)) {
                throw new GuestRegistrationPort.ResultUnknown();
            }
            throw failure;
        }
    }
    private static GuestFact fact(GuestFact fact, String guestNo) {
        require(fact != null && guestNo.equals(fact.guestNo())
                && ("REGISTERED".equals(fact.state()) && fact.userNo() == null && fact.revision() == 1
                || "CLAIMED".equals(fact.state()) && id(fact.userNo()) && fact.revision() == 2));
        return fact;
    }
    private static void current(Scope scope) {
        require(
                scope != null
                        && scope.identity() != null
                        && "MERCHANT".equals(scope.identity().realm())
                        && scope.identity().equals(DelegatedIdentityContext.current()));
    }
    private static boolean id(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid) {
        if (!valid) { throw new IllegalStateException("invalid guest owner response or scope"); }
    }
}
