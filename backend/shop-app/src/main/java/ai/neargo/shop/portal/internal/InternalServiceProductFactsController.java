package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.product.ServiceProductFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Product fact ownership stays in PLATFORM and every internal access requires the platform service credential. */
@RestController
public class InternalServiceProductFactsController {
    private final ServiceProductFactsPort products;
    private final String token;
    public InternalServiceProductFactsController(ServiceProductFactsPort products,
            @Value("${shop.services.internal-token:}") String token) {
        this.products = products;
        this.token = token;
    }
    /** The shared token check precedes product resolution and all business errors retain platform semantics. */
    @PostMapping(ServiceProductFactsInternalApi.PATH)
    public ResponseEntity<?> get(@RequestBody ServiceProductFactsInternalApi.Lookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> products.get(request.entityNo(), request.storeNo(), request.skuNo()));
    }
}
