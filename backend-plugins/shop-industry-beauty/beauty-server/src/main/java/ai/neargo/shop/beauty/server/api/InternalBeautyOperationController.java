package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
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
public class InternalBeautyOperationController {
    private final BoundDelegatedCalls calls;
    private final ServiceOperationQueryService serviceOperationQueryService;
    private final ServiceOperationRecoveryService serviceOperationRecoveryService;

    /** Production dependencies are mandatory; there is no empty provider fallback. */
    public InternalBeautyOperationController(
            ServiceOperationQueryService serviceOperationQueryService,
            ServiceOperationRecoveryService serviceOperationRecoveryService,
            @Value("${shop.services.internal-token:}") String token) {
        calls = new BoundDelegatedCalls(token);
        this.serviceOperationQueryService = serviceOperationQueryService;
        this.serviceOperationRecoveryService = serviceOperationRecoveryService;
    }

    /** The fixed route chooses GET_OPERATION; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/getOperation")
    public ResponseEntity<?> getOperation(
            @RequestBody InternalCall<BeautyReceptionInternalApi.OperationRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BAPI-160",
                "GET_OPERATION",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().operationNo(),
                () -> {
                    return serviceOperationQueryService.getOperation(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().operationNo());
                });
    }

    /** The fixed route chooses RECOVER_OPERATION; the caller cannot dispatch another action. */
    @PostMapping("/internal/beauty/reception/recoverOperation")
    public ResponseEntity<?> recoverOperation(
            @RequestBody InternalCall<BeautyReceptionInternalApi.OperationRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                "BM03-OPERATION-RECOVERY",
                "RECOVER_OPERATION",
                BoundDelegatedCalls.Mode.MERCHANT,
                request.request().operationNo(),
                () -> {
                    return serviceOperationRecoveryService.recoverOperation(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request().operationNo());
                });
    }

    /** Pure JSON decoding can precede authentication; malformed data never reaches an owner. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed() {
        return ResponseEntity.badRequest().build();
    }
}
