package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.beauty.spi.BeautySettlementInternalApi;
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

@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public class InternalWorkOrderSettlementController {
    private final BoundDelegatedCalls calls;
    private final WorkOrderSettlementService service;
    private final Validator validator;
    public InternalWorkOrderSettlementController(WorkOrderSettlementService service, Validator validator,
            @Value("${shop.services.internal-token:}") String token) {
        this.calls = new BoundDelegatedCalls(token);
        this.service = service;
        this.validator = validator;
    }
    @PostMapping(BeautySettlementInternalApi.READ_PATH)
    public ResponseEntity<?> read(@RequestBody InternalCall<BeautySettlementInternalApi.ReadReq> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(token, call, BeautySettlementInternalApi.ENDPOINT, BeautySettlementInternalApi.READ,
                BoundDelegatedCalls.Mode.MERCHANT, call.request().workOrderNo(), () -> {
                    if (!validator.validate(call.request()).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
                    return service.get(call.binding().entityNo(), call.binding().storeNo(),
                            call.binding().identity().actorNo(), call.request().workOrderNo());
                });
    }
    @PostMapping(BeautySettlementInternalApi.PREVIEW_PATH)
    public ResponseEntity<?> preview(@RequestBody InternalCall<BeautySettlementInternalApi.PreviewReq> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return calls.invoke(token, call, BeautySettlementInternalApi.ENDPOINT, BeautySettlementInternalApi.PREVIEW,
                BoundDelegatedCalls.Mode.MERCHANT, call.request().workOrderNo(), () -> {
                    if (!validator.validate(call.request()).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
                    return service.preview(call.binding().entityNo(), call.binding().storeNo(),
                            call.binding().identity().actorNo(), call.request().workOrderNo(), call.request().preview());
                });
    }
}
