package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.svc.client.ServiceCalls;

/** Only complete owner responses matching the authenticated request become shared guest facts. */
public final class RemoteGuestRegistrationPort implements GuestRegistrationPort {
    private final GuestRegistrationInternalApi api;
    public RemoteGuestRegistrationPort(GuestRegistrationInternalApi api) { this.api = api; }
    @Override public Registration register(Scope scope, RegisterCommand command) {
        current(scope);
        var request = new GuestRegistrationInternalApi.Register(scope, command);
        var result = ServiceCalls.call("PLATFORM", () -> api.register(request));
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
        var result = ServiceCalls.call("PLATFORM", () -> api.claim(request));
        require(result != null && request.equals(result.request()) && result.claim() != null);
        var claim = result.claim();
        require(guestNo.equals(claim.guestNo()) && id(claim.userNo()) && claim.revision() == 2);
        return claim;
    }
    private static GuestFact fact(GuestFact fact, String guestNo) {
        require(fact != null && guestNo.equals(fact.guestNo())
                && ("REGISTERED".equals(fact.state()) && fact.userNo() == null && fact.revision() == 1
                || "CLAIMED".equals(fact.state()) && id(fact.userNo()) && fact.revision() == 2));
        return fact;
    }
    private static void current(Scope scope) {
        require(scope != null && scope.identity() != null
                && scope.identity().equals(DelegatedIdentityContext.current()));
    }
    private static boolean id(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid) {
        if (!valid) { throw new IllegalStateException("invalid guest owner response or scope"); }
    }
}
