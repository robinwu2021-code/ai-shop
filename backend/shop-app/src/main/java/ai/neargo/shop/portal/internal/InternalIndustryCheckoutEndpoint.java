package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryCheckoutInternalApi;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalIndustryCheckoutEndpoint {
    private final IndustryCheckoutPort checkout;
    private final String token;
    public InternalIndustryCheckoutEndpoint(IndustryCheckoutPort checkout,
            @Value("${shop.services.internal-token:}") String token) { this.checkout = checkout; this.token = token; }
    @PostMapping(IndustryCheckoutInternalApi.ROOT + "/get")
    public ResponseEntity<?> get(@RequestBody IndustryCheckoutInternalApi.Reference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> checkout.get(request.identity(), request.orderNo()));
    }
    @PostMapping(IndustryCheckoutInternalApi.ROOT + "/pay")
    public ResponseEntity<?> pay(@RequestBody IndustryCheckoutPort.PayCommand command,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> checkout.pay(command));
    }
    @PostMapping(IndustryCheckoutInternalApi.ROOT + "/query-payment")
    public ResponseEntity<?> query(@RequestBody IndustryCheckoutInternalApi.Reference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> checkout.queryPayment(request.identity(), request.orderNo()));
    }
    @PostMapping(IndustryCheckoutInternalApi.ROOT + "/finalize")
    public ResponseEntity<?> finalizeCheckout(@RequestBody IndustryCheckoutInternalApi.Reference request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> checkout.finalizeCheckout(request.identity(), request.orderNo()));
    }
}
