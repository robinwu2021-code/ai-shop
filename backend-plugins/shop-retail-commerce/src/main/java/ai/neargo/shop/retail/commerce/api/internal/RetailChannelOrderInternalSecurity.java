package ai.neargo.shop.retail.commerce.api.internal;

import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Retail uses the established scope signature algorithm with its own keys, exact retail path/body
 * digest and service token.
 */
public final class RetailChannelOrderInternalSecurity {
    private final String token;
    private final List<byte[]> keys;
    private final Clock clock;

    public RetailChannelOrderInternalSecurity(
            String token, String currentKey, String previousKey, Clock clock) {
        this.token = token;
        this.clock = clock;
        keys =
                Stream.of(currentKey, previousKey)
                        .map(FoodScopeCredentials::key)
                        .filter(Objects::nonNull)
                        .toList();
    }

    public ResponseEntity<?> invoke(
            String given, String scope, String path, byte[] body, Supplier<?> operation) {
        try {
            return InternalServiceResponses.invoke(
                    token,
                    given,
                    () -> {
                        var claims =
                                FoodScopeCredentials.verify(
                                        scope,
                                        keys,
                                        FoodScopeCredentials.digest("POST", path, body),
                                        clock.instant().getEpochSecond());
                        if (claims.realm() != FoodScopeCredentials.Realm.SYSTEM)
                            throw new FoodScopeCredentials.Rejected(
                                    403, "SYSTEM retail owner scope required");
                        FoodScopeCredentials.requireBodyMatches(
                                claims, FoodScopeBodyScanner.scan(body));
                        return operation.get();
                    });
        } catch (FoodScopeCredentials.Rejected failure) {
            return ResponseEntity.status(failure.status()).build();
        }
    }
}
