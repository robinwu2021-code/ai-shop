package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalEntitlementCapabilityEndpoint {
    private final EntitlementCapabilityPort owner;
    private final String token;
    public InternalEntitlementCapabilityEndpoint(EntitlementCapabilityPort owner,
            @Value("${shop.services.internal-token:}") String token) { this.owner = owner; this.token = token; }
    @PostMapping(PayInternalPaths.ENT_CAPABILITIES)
    public ResponseEntity<?> status(@RequestBody PayCapabilityRequests.Status request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied) {
        return InternalServiceResponses.invoke(token, supplied, () -> owner.status(request.tenantNo()));
    }
}
