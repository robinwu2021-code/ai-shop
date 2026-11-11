package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalWorkOrderEntitlementFactsController {
    private final ServiceWorkOrderUseFactsPort owner;
    private final String token;
    public InternalWorkOrderEntitlementFactsController(ServiceWorkOrderUseFactsPort owner,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner; this.token = token;
    }

    @PostMapping(ServiceWorkOrderUseInternalApi.FACTS)
    public ResponseEntity<?> inspect(@RequestBody Lookup lookup,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> owner.inspect(lookup));
    }
}
