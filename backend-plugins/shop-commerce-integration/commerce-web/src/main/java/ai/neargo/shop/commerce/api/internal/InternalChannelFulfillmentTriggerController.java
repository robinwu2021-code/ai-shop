package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerInternalApi;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Original service authentication and signed SYSTEM body precede a scoped import lookup; ACK means
 * durable intent only.
 */
@RestController
public class InternalChannelFulfillmentTriggerController {
    private final String token;
    private final List<byte[]> keys;
    private final ChannelFulfillmentTriggerPort trigger;
    private final ObjectMapper json;
    private final Clock clock;

    public InternalChannelFulfillmentTriggerController(
            String token,
            String current,
            String previous,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json,
            Clock clock) {
        this.token = token;
        this.keys =
                Stream.of(current, previous)
                        .map(FoodScopeCredentials::key)
                        .filter(Objects::nonNull)
                        .toList();
        this.trigger = trigger;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping(ChannelFulfillmentTriggerInternalApi.PATH)
    public ResponseEntity<?> record(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        try {
            return InternalServiceResponses.invoke(
                    this.token,
                    token,
                    () -> {
                        var claims =
                                FoodScopeCredentials.verify(
                                        scope,
                                        keys,
                                        FoodScopeCredentials.digest(
                                                "POST",
                                                ChannelFulfillmentTriggerInternalApi.PATH,
                                                body),
                                        clock.instant().getEpochSecond());
                        if (claims.realm() != FoodScopeCredentials.Realm.SYSTEM)
                            throw new FoodScopeCredentials.Rejected(
                                    403, "SYSTEM original fulfillment scope required");
                        FoodScopeCredentials.requireBodyMatches(
                                claims, FoodScopeBodyScanner.scan(body));
                        return trigger.record(
                                CatalogSourceTransportPolicy.read(
                                        json, body, ChannelFulfillmentTriggerPort.Request.class));
                    });
        } catch (FoodScopeCredentials.Rejected rejected) {
            return ResponseEntity.status(rejected.status()).build();
        }
    }
}
