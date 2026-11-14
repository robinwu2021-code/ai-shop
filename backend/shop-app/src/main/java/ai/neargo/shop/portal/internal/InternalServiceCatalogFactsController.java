package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Industry services can read existing owner projections only after platform credential verification. */
@RestController
public class InternalServiceCatalogFactsController {
    private final ServiceCatalogFactsPort facts;
    private final String token;
    public InternalServiceCatalogFactsController(ServiceCatalogFactsPort facts,
            @Value("${shop.services.internal-token:}") String token) {
        this.facts = facts;
        this.token = token;
    }
    @PostMapping(ServiceCatalogFactsInternalApi.STORE_PATH)
    public ResponseEntity<?> store(@RequestBody ServiceCatalogFactsInternalApi.StoreLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> facts.store(request.storeNo()));
    }
    @PostMapping(ServiceCatalogFactsInternalApi.SERVICE_PATH)
    public ResponseEntity<?> service(@RequestBody ServiceCatalogFactsInternalApi.ServiceLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> facts.service(request.storeNo(), request.skuNo()));
    }
}
