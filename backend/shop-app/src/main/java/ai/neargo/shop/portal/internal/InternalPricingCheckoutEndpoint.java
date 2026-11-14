package ai.neargo.shop.portal.internal;

import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestHeader;
import ai.neargo.shop.spi.trade.PricingCheckoutInternalApi;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal token authentication protects this reverse owner endpoint; it is never a consumer pricing API. */
@RestController
public class InternalPricingCheckoutEndpoint {
    private final PricingCheckoutPort port;
    private final String token;
    public InternalPricingCheckoutEndpoint(PricingCheckoutPort port,
            @Value("${shop.services.internal-token:}") String token) { this.port = port; this.token = token; }
    @PostMapping(PricingCheckoutInternalApi.ROOT + "/staff/quote")
    public ResponseEntity<?> quoteStaff(@RequestBody PricingCheckoutInternalApi.StaffQuote r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> port.quoteStaff(r.scope(), r.actorNo(), r.source(), r.selection(), r.key()));
    }
    @PostMapping(PricingCheckoutInternalApi.ROOT + "/customer/quote")
    public ResponseEntity<?> quoteCustomer(@RequestBody PricingCheckoutInternalApi.CustomerQuote r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> port.quoteCustomer(r.source(), r.credentials(), r.selection(), r.key()));
    }
    @PostMapping(PricingCheckoutInternalApi.ROOT + "/staff/get")
    public ResponseEntity<?> getStaff(@RequestBody PricingCheckoutInternalApi.StaffRead r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> port.getStaff(r.scope(), r.actorNo(), r.source(), r.quoteNo()));
    }
    @PostMapping(PricingCheckoutInternalApi.ROOT + "/customer/get")
    public ResponseEntity<?> getCustomer(@RequestBody PricingCheckoutInternalApi.CustomerRead r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> port.getCustomer(r.source(), r.credentials(), r.quoteNo()));
    }
    @PostMapping(PricingCheckoutInternalApi.ROOT + "/staff/revalidate")
    public ResponseEntity<?> revalidateStaff(@RequestBody PricingCheckoutInternalApi.StaffRead r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> port.revalidateStaff(r.scope(), r.actorNo(), r.source(), r.quoteNo(), r.digest()));
    }
    @PostMapping(PricingCheckoutInternalApi.ROOT + "/customer/revalidate")
    public ResponseEntity<?> revalidateCustomer(@RequestBody PricingCheckoutInternalApi.CustomerRead r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> port.revalidateCustomer(r.source(), r.credentials(), r.quoteNo(), r.digest()));
    }
}
