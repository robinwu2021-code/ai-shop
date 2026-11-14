package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol;
import ai.neargo.shop.spi.trade.ServiceExecutionInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.PrepareExecution;
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
public final class InternalServiceExecutionController {
    private final ServiceExecutionPort owner;
    private final String token;
    private final InternalBindings bindings;
    private final BoundDelegatedCalls human;
    public InternalServiceExecutionController(ServiceExecutionPort owner,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner;
        this.token = token;
        this.bindings = new InternalBindings(token);
        this.human = new BoundDelegatedCalls(token);
    }

    @PostMapping(ServiceExecutionInternalApi.PREPARE_PATH)
    public ResponseEntity<?> prepare(@RequestBody InternalCall<PrepareExecution> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human.invoke(given, call, ServiceExecutionInternalApi.ENDPOINT, "PREPARE",
                BoundDelegatedCalls.Mode.MERCHANT,
                call == null ? null : call.request().binding().executionNo(), () -> {
                    var b = call.request().binding();
                    bindings.requireCoordinates(call, b.scope().tenantNo(), b.scope().entityNo(), b.scope().storeNo(),
                            call.binding().identity().actorNo());
                    return owner.prepare(call.request());
                });
    }

    @PostMapping(ServiceExecutionInternalApi.QUERY_PATH)
    public ResponseEntity<?> query(@RequestBody InternalCall<ExecutionLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human.invoke(given, call, ServiceExecutionInternalApi.ENDPOINT, "QUERY",
                BoundDelegatedCalls.Mode.MERCHANT,
                call == null ? null : call.request().binding().executionNo(), () -> {
                    var b = call.request().binding();
                    bindings.requireCoordinates(call, b.scope().tenantNo(), b.scope().entityNo(), b.scope().storeNo(),
                            call.binding().identity().actorNo());
                    return owner.query(call.request());
                });
    }

    @PostMapping(ServiceExecutionInternalApi.QUERY_ORIGINAL_PATH)
    public ResponseEntity<?> queryOriginal(@RequestBody ExecutionOriginalProtocol.Call<ExecutionLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(token, given, () -> bindings.digest(call), () -> {
            var b = call.request().binding();
            bindings.requireRoute(call, ServiceExecutionInternalApi.ENDPOINT, "QUERY_ORIGINAL", b.executionNo());
            bindings.requireCoordinates(call, b.scope().tenantNo(), b.scope().entityNo(), b.scope().storeNo());
            return bindings.reply(call, owner.queryOriginal(call.request()));
        });
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().build();
    }
}
