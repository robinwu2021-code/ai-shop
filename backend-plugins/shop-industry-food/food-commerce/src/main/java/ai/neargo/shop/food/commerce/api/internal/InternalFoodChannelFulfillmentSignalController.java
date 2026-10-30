package ai.neargo.shop.food.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.food.spi.DiningChannelFulfillmentSignalInternalApi;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

/**
 * The original token and signed SYSTEM body are checked before exact original event/custody
 * evidence is read.
 */
@RestController
public final class InternalFoodChannelFulfillmentSignalController {
    private final FoodCommerceInternalSecurity security;
    private final DiningChannelFulfillmentSignalPort owner;
    private final ObjectMapper json;

    public InternalFoodChannelFulfillmentSignalController(
            FoodCommerceInternalSecurity security,
            DiningChannelFulfillmentSignalPort owner,
            ObjectMapper json) {
        this.security = security;
        this.owner = owner;
        this.json = json;
    }

    @PostMapping(DiningChannelFulfillmentSignalInternalApi.PATH)
    public ResponseEntity<?> signal(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                DiningChannelFulfillmentSignalInternalApi.PATH,
                body,
                () ->
                        owner.resolve(
                                CatalogSourceTransportPolicy.read(
                                        json, body, DiningServiceEvent.class)));
    }
}
