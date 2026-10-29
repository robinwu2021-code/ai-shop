package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockReply;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.svc.ExecutionPreparationOriginalBindings;
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

import java.util.function.Supplier;

/** Token gate precedes target decoding; original routes never install delegated identity. */
@RestController
public final class InternalServiceExecutionPreparationController {
    private final ServiceExecutionPreparationPort owner;
    private final String token;
    private final InternalBindings bindings;
    private final ExecutionPreparationOriginalBindings originals;
    private final BoundDelegatedCalls human;

    public InternalServiceExecutionPreparationController(
            ServiceExecutionPreparationPort owner,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner;
        this.token = token;
        this.bindings = new InternalBindings(token);
        this.originals = new ExecutionPreparationOriginalBindings(token);
        this.human = new BoundDelegatedCalls(token);
    }

    @PostMapping(ServiceExecutionPreparationInternalApi.REFERENCE_PATH)
    public ResponseEntity<?> referenceOriginal(
            @RequestBody InternalCall<ExecutionLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return tokenFirst(
                given,
                () -> {
                    var b = call.request().binding();
                    return human.invoke(
                            given,
                            call,
                            ServiceExecutionPreparationInternalApi.ENDPOINT,
                            "REFERENCE_ORIGINAL",
                            BoundDelegatedCalls.Mode.MERCHANT,
                            b.executionNo(),
                            () -> {
                                bindings.requireCoordinates(
                                        call,
                                        b.scope().tenantNo(),
                                        b.scope().entityNo(),
                                        b.scope().storeNo(),
                                        call.binding().identity().actorNo());
                                return owner.referenceOriginal(call.request());
                            });
                });
    }

    @PostMapping(ServiceExecutionPreparationInternalApi.RETAIN_PATH)
    public ResponseEntity<?> retainStock(
            @RequestBody InternalCall<RetainExecutionStock> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return tokenFirst(
                given,
                () -> {
                    var ref = call.request().reference();
                    return human.invoke(
                            given,
                            call,
                            ServiceExecutionPreparationInternalApi.ENDPOINT,
                            "RETAIN_STOCK",
                            BoundDelegatedCalls.Mode.MERCHANT,
                            ref.executionNo(),
                            () -> {
                                bindings.requireCoordinates(
                                        call,
                                        ref.scope().tenantNo(),
                                        ref.scope().entityNo(),
                                        ref.scope().storeNo(),
                                        call.binding().identity().actorNo());
                                return owner.retainStock(call.request());
                            });
                });
    }

    @PostMapping(ServiceExecutionPreparationInternalApi.EVIDENCE_PATH)
    public ResponseEntity<?> queryOriginalEvidence(
            @RequestBody EvidenceCall call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(
                token,
                given,
                () -> originals.digest(call),
                () ->
                        new EvidenceReply(
                                call.binding(),
                                originals.digest(call),
                                owner.queryOriginalEvidence(call.request())));
    }

    @PostMapping(ServiceExecutionPreparationInternalApi.STOCK_PATH)
    public ResponseEntity<?> queryStockOriginal(
            @RequestBody StockCall call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(
                token,
                given,
                () -> originals.digest(call),
                () ->
                        new StockReply(
                                call.binding(),
                                originals.digest(call),
                                owner.queryStockOriginal(call.request())));
    }

    private ResponseEntity<?> tokenFirst(String given, Supplier<ResponseEntity<?>> operation) {
        // Wrapping only the gate avoids nested successful ResponseEntity bodies.
        var gate = InternalServiceResponses.invoke(token, given, () -> Boolean.TRUE);
        if (!gate.getStatusCode().is2xxSuccessful()) return gate;
        try {
            return operation.get();
        } catch (NullPointerException | IllegalArgumentException malformed) {
            return ResponseEntity.badRequest().build();
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformed(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().build();
    }
}
