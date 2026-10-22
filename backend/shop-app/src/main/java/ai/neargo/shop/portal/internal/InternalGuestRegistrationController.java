package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
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
    public InternalGuestRegistrationController(GuestRegistrationPort guests,
            @Value("${shop.services.internal-token:}") String token) { this.guests = guests; this.token = token; }
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
