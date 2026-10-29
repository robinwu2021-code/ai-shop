package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ServiceTaskPreparationService;
import ai.neargo.shop.beauty.spi.BeautyTaskPreparationInternalApi;
import ai.neargo.shop.beauty.spi.BeautyTaskPreparationInternalApi.PrepareTaskCall;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Token-first standalone route is consumed by the public preparations application service. */
@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public final class InternalBeautyTaskPreparationController {
    private final ServiceTaskPreparationService service;
    private final String token;
    private final BoundDelegatedCalls calls;
    private final InternalBindings bindings;

    public InternalBeautyTaskPreparationController(
            ServiceTaskPreparationService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
        this.calls = new BoundDelegatedCalls(token);
        this.bindings = new InternalBindings(token);
    }

    @PostMapping(BeautyTaskPreparationInternalApi.PATH)
    public ResponseEntity<?> prepareTask(
            @RequestBody InternalCall<PrepareTaskCall> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        var gate = InternalServiceResponses.invoke(token, given, () -> Boolean.TRUE);
        if (!gate.getStatusCode().is2xxSuccessful()) return gate;
        try {
            var request = call.request();
            return calls.invoke(
                    given,
                    call,
                    BeautyTaskPreparationInternalApi.ENDPOINT,
                    "PREPARE_TASK",
                    BoundDelegatedCalls.Mode.MERCHANT,
                    request.taskNo(),
                    () -> {
                        bindings.requireCoordinates(
                                call,
                                "MAIN",
                                request.entityNo(),
                                request.storeNo(),
                                request.actorNo());
                        return service.prepareTask(
                                request.entityNo(),
                                request.storeNo(),
                                request.actorNo(),
                                request.workOrderNo(),
                                request.taskNo(),
                                request.req(),
                                request.requestKey());
                    });
        } catch (NullPointerException | IllegalArgumentException malformed) {
            return ResponseEntity.badRequest().build();
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().build();
    }
}
