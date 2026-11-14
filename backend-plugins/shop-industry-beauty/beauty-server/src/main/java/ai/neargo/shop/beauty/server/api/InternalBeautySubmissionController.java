package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
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
public class InternalBeautySubmissionController {
    private final BoundDelegatedCalls calls;
    private final ServiceSubmissionService serviceSubmissionService;

    /** Production dependencies are mandatory; there is no empty provider fallback. */
    public InternalBeautySubmissionController(
            ServiceSubmissionService serviceSubmissionService,
            @Value("${shop.services.internal-token:}") String token) {
        calls = new BoundDelegatedCalls(token);
        this.serviceSubmissionService = serviceSubmissionService;
    }

    /** The fixed route chooses GET_SUBMISSION; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/getSubmission")
    public ResponseEntity<?> getSubmission(
            @RequestBody InternalCall<BeautyReceptionInternalApi.SubmissionRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-072",
                "GET_SUBMISSION",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().submissionNo(),
                () -> {
                    return serviceSubmissionService.getSubmission(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().submissionNo());
                });
    }

    /**
     * The fixed route chooses REQUEST_SUBMISSION_RECOVERY; the caller cannot dispatch another
     * action.
     */
    @PostMapping("/internal/beauty/reception/requestSubmissionRecovery")
    public ResponseEntity<?> requestSubmissionRecovery(
            @RequestBody InternalCall<BeautyReceptionInternalApi.RequestSubmissionRecovery> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-073",
                "REQUEST_SUBMISSION_RECOVERY",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().submissionNo(),
                () -> {
                    return serviceSubmissionService.requestSubmissionRecovery(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().submissionNo(),
                            request.request().req(),
                            request.request().idempotencyKey());
                });
    }

    /** The fixed route chooses RECOVER_PENDING; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/recoverPending")
    public ResponseEntity<?> recoverPending(
            @RequestBody InternalCall<BeautyReceptionInternalApi.SubmissionRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-073",
                "RECOVER_PENDING",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().submissionNo(),
                () -> {
                    return serviceSubmissionService.recoverPending(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().submissionNo());
                });
    }

    /** Pure JSON decoding can precede authentication; malformed data never reaches an owner. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed() {
        return ResponseEntity.badRequest().build();
    }
}
