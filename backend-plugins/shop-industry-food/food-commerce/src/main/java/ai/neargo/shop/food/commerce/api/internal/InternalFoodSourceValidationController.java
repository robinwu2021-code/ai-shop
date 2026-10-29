package ai.neargo.shop.food.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.port.FoodSourceValidationProviderImpl;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationInternalApi;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Evidence validation cannot be exposed by installing a controller without the owner and signed
 * internal transport.
 */
@RestController
public final class InternalFoodSourceValidationController {
    private final FoodCommerceInternalSecurity security;
    private final FoodSourceValidationProviderImpl owner;
    private final ObjectMapper json;
    private final Clock clock;

    public InternalFoodSourceValidationController(
            FoodCommerceInternalSecurity security,
            FoodSourceValidationProviderImpl owner,
            ObjectMapper json,
            Clock clock) {
        this.security = security;
        this.owner = owner;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping(ChannelSourceValidationInternalApi.FOOD_CURRENT_PATH)
    public ResponseEntity<?> current(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                ChannelSourceValidationInternalApi.FOOD_CURRENT_PATH,
                body,
                () -> {
                    Source source;
                    try {
                        source = CatalogSourceTransportPolicy.read(json, body, Source.class);
                    } catch (RuntimeException malformed) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    CatalogSourceTransportPolicy.validateSource(source);
                    return owner.current(source);
                });
    }

    @PostMapping(ChannelSourceValidationInternalApi.FOOD_VALIDATE_PATH)
    public ResponseEntity<?> validate(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                ChannelSourceValidationInternalApi.FOOD_VALIDATE_PATH,
                body,
                () -> {
                    Source source;
                    try {
                        source = CatalogSourceTransportPolicy.read(json, body, Source.class);
                    } catch (RuntimeException malformed) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    CatalogSourceTransportPolicy.validateSource(source);
                    var result = owner.validate(source);
                    CatalogSourceTransportPolicy.validateProof(source, result, clock);
                    return result;
                });
    }
}
