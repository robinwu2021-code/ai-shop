package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.beauty.port.ServiceExecutionSourcePortImpl;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol;
import ai.neargo.shop.spi.trade.ServiceExecutionSourceInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Token-first fixed routes distinguish current human permission from exact original machine evidence. */
@RestController
@ConditionalOnBeautyEnabled
public final class InternalBeautyExecutionSourceController {
    private final ServiceExecutionSourcePortImpl owner;
    private final String token;
    private final InternalBindings bindings;
    private final BoundDelegatedCalls human;
    public InternalBeautyExecutionSourceController(ServiceExecutionSourcePortImpl owner,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner;
        this.token = token;
        this.bindings = new InternalBindings(token);
        this.human = new BoundDelegatedCalls(token);
    }

    @PostMapping(ServiceExecutionSourceInternalApi.INSPECT_PATH)
    public ResponseEntity<?> inspect(@RequestBody InternalCall<StartIntentLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human.invoke(given, call, ServiceExecutionSourceInternalApi.ENDPOINT, "INSPECT",
                BoundDelegatedCalls.Mode.MERCHANT,
                call == null ? null : call.request().binding().executionNo(), () -> {
                    var b = call.request().binding();
                    bindings.requireCoordinates(call, b.scope().tenantNo(), b.scope().entityNo(), b.scope().storeNo(),
                            call.binding().identity().actorNo());
                    return owner.inspect(call.request());
                });
    }

    @PostMapping(ServiceExecutionSourceInternalApi.INSPECT_ORIGINAL_PATH)
    public ResponseEntity<?> inspectOriginal(@RequestBody ExecutionOriginalProtocol.Call<StartIntentLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(token, given, () -> bindings.digest(call), () -> {
            var b = call.request().binding();
            bindings.requireRoute(call, ServiceExecutionSourceInternalApi.ENDPOINT, "INSPECT_ORIGINAL",
                    b.executionNo());
            bindings.requireCoordinates(call, b.scope().tenantNo(), b.scope().entityNo(), b.scope().storeNo());
            return bindings.reply(call, owner.inspectOriginal(call.request()));
        });
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().build();
    }
}
