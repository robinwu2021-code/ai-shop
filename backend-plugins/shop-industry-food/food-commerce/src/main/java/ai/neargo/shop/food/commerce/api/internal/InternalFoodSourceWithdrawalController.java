package ai.neargo.shop.food.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.policy.SourceWithdrawalPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.port.FoodSourceWithdrawalProvider;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalInternalApi;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@RestController
public final class InternalFoodSourceWithdrawalController {
    private final FoodCommerceInternalSecurity security;
    private final FoodSourceWithdrawalProvider owner;
    private final ObjectMapper json;
    private final Clock clock;

    public InternalFoodSourceWithdrawalController(
            FoodCommerceInternalSecurity security,
            FoodSourceWithdrawalProvider owner,
            ObjectMapper json,
            Clock clock) {
        this.security = security;
        this.owner = owner;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping(ChannelSourceWithdrawalInternalApi.FOOD_CAPTURE_PATH)
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                ChannelSourceWithdrawalInternalApi.FOOD_CAPTURE_PATH,
                body,
                () -> {
                    var query = read(body);
                    var proof = owner.capture(query);
                    SourceWithdrawalPolicy.validate(query, proof, clock);
                    return proof;
                });
    }

    @PostMapping(ChannelSourceWithdrawalInternalApi.FOOD_DISPOSITION_PATH)
    public ResponseEntity<?> disposition(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                ChannelSourceWithdrawalInternalApi.FOOD_DISPOSITION_PATH,
                body,
                () -> {
                    var query = read(body);
                    var proof = owner.disposition(query);
                    SourceWithdrawalPolicy.disposition(query, proof, clock);
                    return proof;
                });
    }

    private ChannelSourceWithdrawalProvider.Query read(byte[] body) {
        try {
            return CatalogSourceTransportPolicy.read(
                    json, body, ChannelSourceWithdrawalProvider.Query.class);
        } catch (RuntimeException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
