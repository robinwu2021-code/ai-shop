package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Concrete private routes use one token/context lifecycle and the existing business owners. */
@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public class InternalBeautyGuestController {
    private final BoundDelegatedCalls calls;
    private final GuestRegistrationService guestRegistrationService;

    /** Production dependencies are mandatory; there is no empty provider fallback. */
    public InternalBeautyGuestController(
            GuestRegistrationService guestRegistrationService,
            @Value("${shop.services.internal-token:}") String token) {
        calls = new BoundDelegatedCalls(token);
        this.guestRegistrationService = guestRegistrationService;
    }

    /** The fixed route chooses REGISTER_GUEST; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/registerGuest")
    public ResponseEntity<?> registerGuest(
            @RequestBody InternalCall<BeautyReceptionInternalApi.RegisterGuest> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-031",
                "REGISTER_GUEST",
                BoundDelegatedCalls.Mode.MERCHANT,
                null,
                () -> {
                    return guestRegistrationService.registerGuest(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses CLAIM_GUEST; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/claimGuest")
    public ResponseEntity<?> claimGuest(
            @RequestBody InternalCall<BeautyReceptionInternalApi.ClaimGuest> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-032",
                "CLAIM_GUEST",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().guestNo(),
                () -> {
                    return guestRegistrationService.claimGuest(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().guestNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** Pure JSON decoding can precede authentication; malformed data never reaches an owner. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed() {
        return ResponseEntity.badRequest().build();
    }
}
