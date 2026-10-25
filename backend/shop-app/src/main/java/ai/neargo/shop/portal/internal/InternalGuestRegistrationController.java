package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.auth.BoundDelegatedCalls.Mode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi.CurrentClaimRequest;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi.CurrentClaimResponse;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi.CurrentClaimsRequest;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi.CurrentClaimsResponse;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Service tokens authenticate transport only; the owner rechecks current full operator and store authority. */
@RestController
public class InternalGuestRegistrationController {
    private final GuestRegistrationPort guests;
    private final String token;
    private final BoundDelegatedCalls bound;

    public InternalGuestRegistrationController(
            GuestRegistrationPort guests, @Value("${shop.services.internal-token:}") String token) {
        this.guests = guests;
        this.token = token;
        this.bound = new BoundDelegatedCalls(token);
    }

    /**
     * Claimed guest selection remains merchant identity evidence and never grants C read authority.
     */
    @PostMapping(GuestRegistrationInternalApi.BASE + "resolve-claimed-customer")
    public ResponseEntity<?> resolveClaimedCustomer(
            @RequestBody InternalCall<GuestRegistrationInternalApi.Verify> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        String target = call == null ? null : call.request().guestNo();
        return bound.invoke(
                given,
                call,
                GuestRegistrationInternalApi.COUNTER_CUSTOMER_ENDPOINT,
                "RESOLVE",
                Mode.MERCHANT,
                target,
                () -> {
                    var request = call.request();
                    var scope = request.scope();
                    require(
                            scope != null
                                    && scope.identity().equals(call.binding().identity())
                                    && scope.entityNo().equals(call.binding().entityNo())
                                    && scope.storeNo().equals(call.binding().storeNo()));
                    return new GuestRegistrationInternalApi.ClaimedCustomerResolution(
                            request,
                            guests.resolveClaimedCustomer(
                                    scope, request.guestNo(), request.claimToken()));
                });
    }

    /**
     * Consumer current ownership has its own exact purpose and never enters merchant get/verify.
     */
    @PostMapping(GuestRegistrationInternalApi.BASE + "current-claim")
    public ResponseEntity<?> currentClaim(
            @RequestBody InternalCall<CurrentClaimRequest> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        String target =
                call == null || call.request() == null || call.request().guest() == null
                        ? null
                        : call.request().guest().guestNo();
        return bound.invoke(
                given,
                call,
                GuestRegistrationInternalApi.CURRENT_ENDPOINT,
                "CURRENT_CLAIM",
                Mode.CONSUMER,
                target,
                () -> {
                    var request = call.request();
                    require(
                            request.scope() != null
                                    && request.guest() != null
                                    && request.scope().identity().equals(call.binding().identity())
                                    && call.binding().entityNo() == null
                                    && call.binding().storeNo() == null);
                    return new CurrentClaimResponse(
                            request, guests.currentClaim(request.scope(), request.guest()));
                });
    }

    /** A complete member-owned claim set is required for safe historical order pagination. */
    @PostMapping(GuestRegistrationInternalApi.BASE + "current-claims")
    public ResponseEntity<?> currentClaims(
            @RequestBody InternalCall<CurrentClaimsRequest> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        String target = call == null ? null : call.binding().identity().actorNo();
        return bound.invoke(
                given,
                call,
                GuestRegistrationInternalApi.CURRENT_ENDPOINT,
                "CURRENT_CLAIMS",
                Mode.CONSUMER,
                target,
                () -> {
                    var request = call.request();
                    require(
                            request.scope() != null
                                    && request.scope().identity().equals(call.binding().identity())
                                    && call.binding().entityNo() == null
                                    && call.binding().storeNo() == null);
                    return new CurrentClaimsResponse(
                            request, guests.currentClaims(request.scope()));
                });
    }

    @PostMapping(GuestRegistrationInternalApi.BASE + "register")
    public ResponseEntity<?> register(@RequestBody GuestRegistrationInternalApi.Register request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> {
            require(request != null && request.command() != null);
            return new GuestRegistrationInternalApi.Registered(request,
                    delegate(request.scope(), () -> guests.register(request.scope(), request.command())));
        });
    }
    @PostMapping(GuestRegistrationInternalApi.BASE + "get")
    public ResponseEntity<?> get(@RequestBody GuestRegistrationInternalApi.Get request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> {
            require(request != null);
            return new GuestRegistrationInternalApi.Found(request,
                    delegate(request.scope(), () -> guests.get(request.scope(), request.guestNo())));
        });
    }
    @PostMapping(GuestRegistrationInternalApi.BASE + "verify")
    public ResponseEntity<?> verify(@RequestBody GuestRegistrationInternalApi.Verify request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> {
            require(request != null);
            return new GuestRegistrationInternalApi.Verified(request, delegate(request.scope(),
                    () -> guests.verify(request.scope(), request.guestNo(), request.claimToken())));
        });
    }
    @PostMapping(GuestRegistrationInternalApi.BASE + "claim")
    public ResponseEntity<?> claim(@RequestBody GuestRegistrationInternalApi.ClaimRequest request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> {
            require(request != null && request.command() != null);
            return new GuestRegistrationInternalApi.Claimed(request,
                    delegate(request.scope(),
                            () -> guests.claim(request.scope(), request.guestNo(), request.command())));
        });
    }
    private ResponseEntity<?> invoke(String given, Supplier<?> operation) {
        try { return InternalServiceResponses.invoke(token, given, operation); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    private <T> T delegate(Scope scope, Supplier<T> operation) {
        require(scope != null && scope.identity() != null);
        if (!"MERCHANT".equals(scope.identity().realm())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return DelegatedIdentityContext.call(scope.identity(), operation);
    }
    private static void require(boolean valid) { if (!valid) { throw BizException.of(ErrorCode.BAD_REQUEST); } }
    /** Public error advice must not turn internal malformed JSON into a successful customer fact. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
