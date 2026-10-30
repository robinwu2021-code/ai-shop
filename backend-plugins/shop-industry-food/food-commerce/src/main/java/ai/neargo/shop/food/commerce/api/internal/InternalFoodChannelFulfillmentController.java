package ai.neargo.shop.food.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.food.commerce.port.FoodChannelFulfillmentSourcePortImpl;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

/**
 * Signed body + original service token; capture is read-only and does not confirm core delivery.
 */
@RestController
public final class InternalFoodChannelFulfillmentController {
    private final FoodCommerceInternalSecurity security;
    private final FoodChannelFulfillmentSourcePortImpl owner;
    private final ObjectMapper json;

    public InternalFoodChannelFulfillmentController(
            FoodCommerceInternalSecurity security,
            FoodChannelFulfillmentSourcePortImpl owner,
            ObjectMapper json) {
        this.security = security;
        this.owner = owner;
        this.json = json;
    }

    @PostMapping("/internal/food/channel-fulfillment/capture")
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-fulfillment/capture",
                body,
                () ->
                        owner.capture(
                                CatalogSourceTransportPolicy.read(
                                        json, body, ChannelFulfillmentSourcePort.Query.class)));
    }
}
