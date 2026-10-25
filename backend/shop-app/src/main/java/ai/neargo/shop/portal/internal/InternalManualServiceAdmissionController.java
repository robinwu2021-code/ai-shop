package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exact token/endpoint/action/target/scope binding precedes current canonical owner authorization.
 */
@RestController
public class InternalManualServiceAdmissionController {
    private final ManualServiceAdmissionPort manual;
    private final BoundDelegatedCalls calls;
    private final InternalBindings bindings;

    public InternalManualServiceAdmissionController(
            ManualServiceAdmissionPort manual,
            @Value("${shop.services.internal-token:}") String token) {
        this.manual = manual;
        calls = new BoundDelegatedCalls(token);
        bindings = new InternalBindings(token);
    }

    @PostMapping(ManualServiceAdmissionInternalApi.BASE + "prepare")
    public ResponseEntity<?> prepare(
            @RequestBody InternalCall<Scoped<PrepareCheckoutReq>> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return calls.invoke(
                given,
                request,
                ManualServiceAdmissionInternalApi.ENDPOINT,
                "PREPARE",
                BoundDelegatedCalls.Mode.MERCHANT,
                request == null ? null : request.request().request().operation().operationNo(),
                () -> {
                    var scope = request.request().scope();
                    bindings.requireCoordinates(
                            request,
                            scope.tenantNo(),
                            scope.entityNo(),
                            scope.storeNo(),
                            scope.actorNo());
                    return manual.prepare(scope, request.request().request());
                });
    }

    @PostMapping(ManualServiceAdmissionInternalApi.BASE + "confirm")
    public ResponseEntity<?> confirm(
            @RequestBody InternalCall<Scoped<ConfirmManualReceipt>> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return calls.invoke(
                given,
                request,
                ManualServiceAdmissionInternalApi.ENDPOINT,
                "CONFIRM",
                BoundDelegatedCalls.Mode.MERCHANT,
                request == null ? null : request.request().request().operation().operationNo(),
                () -> {
                    var scope = request.request().scope();
                    bindings.requireCoordinates(
                            request,
                            scope.tenantNo(),
                            scope.entityNo(),
                            scope.storeNo(),
                            scope.actorNo());
                    return manual.confirm(scope, request.request().request());
                });
    }

    @PostMapping(ManualServiceAdmissionInternalApi.BASE + "query")
    public ResponseEntity<?> query(
            @RequestBody InternalCall<Scoped<ManualLookup>> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return calls.invoke(
                given,
                request,
                ManualServiceAdmissionInternalApi.ENDPOINT,
                "QUERY",
                BoundDelegatedCalls.Mode.MERCHANT,
                request == null ? null : request.request().request().operation().operationNo(),
                () -> {
                    var scope = request.request().scope();
                    bindings.requireCoordinates(
                            request,
                            scope.tenantNo(),
                            scope.entityNo(),
                            scope.storeNo(),
                            scope.actorNo());
                    return manual.query(scope, request.request().request());
                });
    }

    @PostMapping(ManualServiceAdmissionInternalApi.BASE + "source")
    public ResponseEntity<?> source(
            @RequestBody InternalCall<Scoped<SourceLookup>> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return calls.invoke(
                given,
                request,
                ManualServiceAdmissionInternalApi.ENDPOINT,
                "SOURCE",
                BoundDelegatedCalls.Mode.MERCHANT,
                request == null ? null : request.request().request().operation().operationNo(),
                () -> {
                    var scope = request.request().scope();
                    bindings.requireCoordinates(
                            request,
                            scope.tenantNo(),
                            scope.entityNo(),
                            scope.storeNo(),
                            scope.actorNo());
                    return manual.source(scope, request.request().request());
                });
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
