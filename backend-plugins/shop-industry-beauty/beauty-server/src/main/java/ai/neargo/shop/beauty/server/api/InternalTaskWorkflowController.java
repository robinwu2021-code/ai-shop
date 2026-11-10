package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.beauty.spi.BeautyTaskWorkflowInternalApi;
import ai.neargo.shop.beauty.spi.BeautyTaskWorkflowInternalApi.Target;
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

import java.util.function.Supplier;

/** Authenticated fixed routes validate format after bound realm, then use the same actual owner. */
@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public class InternalTaskWorkflowController {
    private final BoundDelegatedCalls calls;
    private final ServiceTaskWorkflowService service;
    private final Validator validator;

    public InternalTaskWorkflowController(
            ServiceTaskWorkflowService service,
            Validator validator,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.validator = validator;
        this.calls = new BoundDelegatedCalls(token);
    }

    private ResponseEntity<?> invoke(
            String token, InternalCall<? extends Target> call, String action, Supplier<?> body) {
        return calls.invoke(
                token,
                call,
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                action,
                BoundDelegatedCalls.Mode.MERCHANT,
                call.request().taskNo(),
                () -> {
                    if (!validator.validate(call.request()).isEmpty())
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    return body.get();
                });
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/accept")
    public ResponseEntity<?> accept(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Accept> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "ACCEPT",
                () ->
                        service.accept(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo(),
                                call.request().request(),
                                call.request().key()));
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/start")
    public ResponseEntity<?> start(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Start> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "START",
                () ->
                        service.start(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo(),
                                call.request().request(),
                                call.request().key()));
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/propose-handover")
    public ResponseEntity<?> proposeHandover(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Handover> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "PROPOSE_HANDOVER",
                () ->
                        service.proposeHandover(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo(),
                                call.request().request(),
                                call.request().key()));
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/act")
    public ResponseEntity<?> act(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Act> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "ACT",
                () ->
                        service.act(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo(),
                                call.request().request(),
                                call.request().key()));
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/query")
    public ResponseEntity<?> query(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Query> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "QUERY",
                () ->
                        service.query(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo()));
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/recover")
    public ResponseEntity<?> recover(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Recover> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "RECOVER",
                () ->
                        service.recover(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo(),
                                call.request().operationNo()));
    }

    @PostMapping(BeautyTaskWorkflowInternalApi.PATH + "/cancel")
    public ResponseEntity<?> cancelPending(
            @RequestBody InternalCall<BeautyTaskWorkflowInternalApi.Cancel> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token) {
        return invoke(
                token,
                call,
                "CANCEL_PENDING",
                () ->
                        service.cancelPending(
                                call.binding().entityNo(),
                                call.binding().storeNo(),
                                call.binding().identity().actorNo(),
                                call.request().workOrderNo(),
                                call.request().taskNo(),
                                call.request().operationNo()));
    }
}
