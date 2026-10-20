package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayOwnerRequests;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Runtime clients can resolve ownership; they cannot register, verify or switch owners over HTTP. */
@RestController
public class InternalEntitlementOwnerEndpoint {
    private final EntitlementOwnerRegistryPort owner;
    private final String token;
    public InternalEntitlementOwnerEndpoint(EntitlementOwnerRegistryPort owner,
            @Value("${shop.services.internal-token:}") String token) { this.owner=owner; this.token=token; }
    @PostMapping(PayInternalPaths.OWNER_RESOLVE)
    public ResponseEntity<?> resolve(@RequestBody PayOwnerRequests.Resolve r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> owner.resolve(r.context(),r.source()));
    }
}
