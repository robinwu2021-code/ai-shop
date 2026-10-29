package ai.neargo.shop.retail.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.retail.commerce.port.OriginalRetailCatalogAvailability;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

/**
 * Original retail store/market owners are queried only after independent retail signature and
 * service-token verification.
 */
@RestController
public final class InternalRetailStoreBusinessAvailabilityController {
    private final RetailChannelOrderInternalSecurity security;
    private final OriginalRetailCatalogAvailability source;
    private final ObjectMapper json;

    public InternalRetailStoreBusinessAvailabilityController(
            RetailChannelOrderInternalSecurity security,
            OriginalRetailCatalogAvailability source,
            ObjectMapper json) {
        this.security = security;
        this.source = source;
        this.json = json;
    }

    @PostMapping("/internal/retail/commerce/store-availability/capture")
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/commerce/store-availability/capture",
                body,
                () -> source.capture(CatalogSourceTransportPolicy.read(json, body, Query.class)));
    }
}
