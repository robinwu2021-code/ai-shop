package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.trade.CheckoutInternalApi;
import ai.neargo.shop.spi.trade.CheckoutInternalApi.Result;
import ai.neargo.shop.spi.trade.CheckoutInternalApi.Scoped;
import ai.neargo.shop.spi.trade.CheckoutPort;
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

/** Current merchant permission is checked by the shared owner after transport authentication. */
@RestController
public class InternalCheckoutController {
    private final CheckoutPort checkout;
    private final String token;

    /** Use shared transport authentication before the owner checks current merchant authority. */
    public InternalCheckoutController(
            CheckoutPort checkout, @Value("${shop.services.internal-token:}") String token) {
        this.checkout = checkout;
        this.token = token;
    }

    /** Publish a quote without interpreting merchant delegation as buyer consent. */
    @PostMapping(CheckoutInternalApi.BASE + "prepare")
    public ResponseEntity<?> prepare(
            @RequestBody Scoped<CheckoutPort.PrepareCheckoutReq> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(request, given, () -> checkout.prepare(request.scope(), request.request()));
    }

    /** Read only through the owner's current merchant permission and data scope. */
    @PostMapping(CheckoutInternalApi.BASE + "readQuote")
    public ResponseEntity<?> readQuote(
            @RequestBody Scoped<CheckoutPort.OperationRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(request, given, () -> checkout.readQuote(request.scope(), request.request()));
    }

    /** Recover durable original order identity after a caller loses a response. */
    @PostMapping(CheckoutInternalApi.BASE + "findOperation")
    public ResponseEntity<?> findOperation(
            @RequestBody Scoped<CheckoutPort.OperationRef> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(
                request, given, () -> checkout.findOperation(request.scope(), request.request()));
    }

    /** Return verified historical source facts; this route cannot authorize a purchase. */
    @PostMapping(CheckoutInternalApi.BASE + "source")
    public ResponseEntity<?> source(
            @RequestBody Scoped<CheckoutPort.SourceLookup> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(request, given, () -> checkout.source(request.scope(), request.request()));
    }

    /**
     * The delegated merchant may read owner facts but cannot initiate or obtain consumer
     * parameters.
     */
    @PostMapping(CheckoutInternalApi.BASE + "findPayment")
    public ResponseEntity<?> findPayment(
            @RequestBody Scoped<CheckoutPort.PaymentLookup> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(
                request, given, () -> checkout.findPayment(request.scope(), request.request()));
    }

    private ResponseEntity<?> invoke(Scoped<?> request, String given, Supplier<?> work) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        DelegatedIdentityContext.call(
                                request.identity(), () -> new Result<>(1, work.get())));
    }

    /** Omit rejected request contents because they can contain reusable identity references. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
