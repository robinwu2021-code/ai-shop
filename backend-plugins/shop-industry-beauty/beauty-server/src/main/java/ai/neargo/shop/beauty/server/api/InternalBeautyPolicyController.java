package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.dto.StorePolicyVO;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.beauty.spi.BeautyPolicyInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal security checks credentials; only the trusted platform may delegate the authenticated actor. */
@RestController
@ConditionalOnBeautyEnabled
public class InternalBeautyPolicyController {
    private final StorePolicyService service;
    private final String token;
    public InternalBeautyPolicyController(StorePolicyService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    /** Returns the raw internal contract; service credentials and delegated scope are checked independently. */
    @PostMapping(BeautyPolicyInternalApi.READ_PATH)
    public ResponseEntity<?> getPolicy(@Valid @RequestBody BeautyPolicyInternalApi.Lookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> InternalBeautyActor.call(request.actorNo(),
                () -> service.getPolicy(request.entityNo(), request.storeNo(), request.actorNo(), request.query())));
    }

    /** Restores the previous audit identity even when publication fails or replays an existing result. */
    @PostMapping(BeautyPolicyInternalApi.PUBLISH_PATH)
    public ResponseEntity<?> publishPolicyVersion(@Valid @RequestBody BeautyPolicyInternalApi.Publication request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> InternalBeautyActor.call(request.actorNo(),
                () -> service.publishPolicyVersion(request.entityNo(), request.storeNo(), request.actorNo(),
                        request.req(), request.idempotencyKey())));
    }
}
