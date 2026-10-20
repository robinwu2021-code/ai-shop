package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayMarketingRequests;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Owner attribution reads are scoped by entity and original usage after internal transport authentication. */
@RestController
public class InternalEntitlementMarketingEndpoint {
    private final EntitlementMarketingFactsPort owner;
    private final String token;
    public InternalEntitlementMarketingEndpoint(EntitlementMarketingFactsPort owner,
            @Value("${shop.services.internal-token:}") String token) { this.owner=owner; this.token=token; }
    @PostMapping(PayInternalPaths.MARKETING_USAGE)
    public ResponseEntity<?> usage(@RequestBody PayMarketingRequests.Usage r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> owner.usage(r.context(),r.entityNo(),r.usageNo(),
                r.afterActivityNo(),r.limit()));
    }
}
