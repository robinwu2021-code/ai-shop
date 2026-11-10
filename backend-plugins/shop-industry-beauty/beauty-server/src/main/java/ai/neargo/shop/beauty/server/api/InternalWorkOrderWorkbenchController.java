package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.beauty.spi.BeautyWorkOrderWorkbenchInternalApi;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalHttp;

import jakarta.validation.Validator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticate token and bound actor before entering the same live authorized owner as embedded.
 */
@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public class InternalWorkOrderWorkbenchController {
    private final BoundDelegatedCalls calls;
    private final WorkOrderWorkbenchService service;
    private final Validator validator;

    public InternalWorkOrderWorkbenchController(
            WorkOrderWorkbenchService service,
            Validator validator,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.validator = validator;
        this.calls = new BoundDelegatedCalls(token);
    }

    /** Payload format is checked once only after token, route and delegated realm are established. */
    @PostMapping(BeautyWorkOrderWorkbenchInternalApi.PATH)
    public ResponseEntity<?> list(
            @RequestBody InternalCall<WorkOrderQueryReq> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(
                token,
                request,
                BeautyWorkOrderWorkbenchInternalApi.ENDPOINT,
                BeautyWorkOrderWorkbenchInternalApi.ACTION,
                BoundDelegatedCalls.Mode.MERCHANT,
                request.binding().storeNo(),
                () -> {
                    if (!validator.validate(request.request()).isEmpty()) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return service.list(
                            request.binding().entityNo(),
                            request.binding().storeNo(),
                            request.binding().identity().actorNo(),
                            request.request());
                });
    }
}
