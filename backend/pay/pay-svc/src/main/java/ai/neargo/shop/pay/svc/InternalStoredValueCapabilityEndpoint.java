package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Capability reads cannot bypass transport authentication or mutate stored-value accounts. */
@RestController
public class InternalStoredValueCapabilityEndpoint {
    private final StoredValueCapabilityPort owner;
    private final String token;
    public InternalStoredValueCapabilityEndpoint(StoredValueCapabilityPort owner,
            @Value("${shop.services.internal-token:}") String token) { this.owner=owner; this.token=token; }
    @PostMapping(PayInternalPaths.SV_CAPABILITIES)
    public ResponseEntity<?> status(@RequestBody PayStoredValueRequests.Capabilities r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> owner.status(r.tenantNo()));
    }
    @PostMapping(PayInternalPaths.SV_PRICING_CAPABILITIES)
    public ResponseEntity<?> pricing(@RequestBody PayCapabilityRequests.Pricing request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied) {
        return InternalServiceResponses.invoke(token, supplied, () -> owner.pricing(request.scope()));
    }
}
