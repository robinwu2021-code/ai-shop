package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.spi.BeautyDefinitionInternalApi;
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
public class InternalBeautyDefinitionController {
    private final ServiceDefinitionService service;
    private final String token;
    public InternalBeautyDefinitionController(ServiceDefinitionService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    /** Returns the raw internal contract; service credentials and delegated scope are checked independently. */
    @PostMapping(BeautyDefinitionInternalApi.READ_PATH)
    public ResponseEntity<?> listDefinitions(@Valid @RequestBody BeautyDefinitionInternalApi.Lookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> InternalBeautyActor.call(request.actorNo(),
                () -> service.listDefinitions(request.entityNo(), request.storeNo(), request.actorNo(),
                        request.query())));
    }

    /** Restores the previous audit identity even when publication fails or replays an existing result. */
    @PostMapping(BeautyDefinitionInternalApi.PUBLISH_PATH)
    public ResponseEntity<?> publishDefinitionVersion(
            @Valid @RequestBody BeautyDefinitionInternalApi.Publication request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> InternalBeautyActor.call(request.actorNo(),
                () -> service.publishDefinitionVersion(request.entityNo(), request.storeNo(), request.actorNo(),
                        request.req(), request.idempotencyKey())));
    }
}
