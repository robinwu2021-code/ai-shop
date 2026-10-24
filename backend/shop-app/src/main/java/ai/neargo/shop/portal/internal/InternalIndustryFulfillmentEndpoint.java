package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Token admission precedes original-source/store/version/code authorization. */
@RestController
public class InternalIndustryFulfillmentEndpoint {
    private final IndustryFulfillmentPort owner;
    private final String token;
    public InternalIndustryFulfillmentEndpoint(IndustryFulfillmentPort owner,
            @Value("${shop.services.internal-token:}") String token) { this.owner=owner; this.token=token; }
    @PostMapping(IndustryFulfillmentInternalApi.ROOT+"/pickup/verify")
    public ResponseEntity<?> verify(@RequestBody IndustryFulfillmentInternalApi.Verify c,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> owner.verifyPickup(c.command(),c.key()));
    }
    @PostMapping(IndustryFulfillmentInternalApi.ROOT+"/query")
    public ResponseEntity<?> query(@RequestBody IndustryFulfillmentInternalApi.Query c,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> owner.query(c.reference(),c.actorNo(),c.key()));
    }
}
