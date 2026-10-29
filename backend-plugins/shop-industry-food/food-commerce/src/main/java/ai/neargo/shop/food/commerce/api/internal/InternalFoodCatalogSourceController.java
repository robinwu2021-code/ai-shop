package ai.neargo.shop.food.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.port.FoodCatalogSourcePortImpl;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Owner controllers remain in the FOOD bridge; auth is checked before decoding private scope or
 * owner calls.
 */
@RestController
public final class InternalFoodCatalogSourceController {
    private final FoodCommerceInternalSecurity security;
    private final FoodCatalogSourcePortImpl owner;
    private final ObjectMapper json;
    private final Clock clock;

    public InternalFoodCatalogSourceController(
            FoodCommerceInternalSecurity security,
            FoodCatalogSourcePortImpl owner,
            ObjectMapper json,
            Clock clock) {
        this.security = security;
        this.owner = owner;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping(ChannelCatalogSourceInternalApi.FOOD_CAPTURE_PATH)
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                ChannelCatalogSourceInternalApi.FOOD_CAPTURE_PATH,
                body,
                () -> {
                    CaptureCommand command;
                    try {
                        command =
                                CatalogSourceTransportPolicy.read(json, body, CaptureCommand.class);
                    } catch (RuntimeException malformed) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    CatalogSourceTransportPolicy.validateCommand(command, clock);
                    return CatalogSourceTransportPolicy.validateDocument(
                            command, owner.capture(command), json, clock);
                });
    }
}
