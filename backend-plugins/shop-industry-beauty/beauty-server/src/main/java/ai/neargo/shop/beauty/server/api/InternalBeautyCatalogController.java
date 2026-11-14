package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.spi.BeautyCatalogInternalApi;
import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Service credentials protect all internal lookups; public reads carry no fabricated account identity. */
@RestController
@ConditionalOnBeautyEnabled
public class InternalBeautyCatalogController {
    private final ServiceCatalogQueryService catalog;
    private final String token;
    public InternalBeautyCatalogController(ServiceCatalogQueryService catalog,
            @Value("${shop.services.internal-token:}") String token) {
        this.catalog = catalog;
        this.token = token;
    }
    @PostMapping(BeautyCatalogInternalApi.STORE_PATH)
    public ResponseEntity<?> store(@RequestBody ServiceCatalogFactsInternalApi.StoreLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> catalog.getPublicStore(request.storeNo()));
    }
    @PostMapping(BeautyCatalogInternalApi.PUBLIC_SERVICE_PATH)
    public ResponseEntity<?> publicService(@RequestBody BeautyCatalogInternalApi.PublicLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () ->
                catalog.getPublicService(request.skuNo(), request.query()));
    }
    @PostMapping(BeautyCatalogInternalApi.SERVICE_PATH)
    public ResponseEntity<?> service(@RequestBody BeautyCatalogInternalApi.MerchantLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> InternalBeautyActor.call(request.actorNo(),
                () -> catalog.getService(request.entityNo(), request.storeNo(), request.actorNo(),
                        request.skuNo(), request.query())));
    }
}
