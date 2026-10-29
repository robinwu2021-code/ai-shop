package ai.neargo.shop.food.commerce.api.internal;

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
 * Embedded and standalone routes both require the original service token AND a signed SYSTEM
 * request body.
 */
public final class FoodCommerceInternalSecurity {
    private final String token;
    private final List<byte[]> keys;
    private final Clock clock;

    public FoodCommerceInternalSecurity(
            String token, String currentKey, String previousKey, Clock clock) {
        this.token = token;
        this.clock = clock;
        this.keys =
                Stream.of(currentKey, previousKey)
                        .map(FoodScopeCredentials::key)
                        .filter(Objects::nonNull)
                        .toList();
    }

    public ResponseEntity<?> invoke(
            String givenToken, String scope, String path, byte[] body, Supplier<?> action) {
        try {
            return InternalServiceResponses.invoke(
                    token,
                    givenToken,
                    () -> {
                        var claims =
                                FoodScopeCredentials.verify(
                                        scope,
                                        keys,
                                        FoodScopeCredentials.digest("POST", path, body),
                                        clock.instant().getEpochSecond());
                        if (claims.realm() != FoodScopeCredentials.Realm.SYSTEM)
                            throw new FoodScopeCredentials.Rejected(
                                    403, "SYSTEM owner scope required");
                        FoodScopeCredentials.requireBodyMatches(
                                claims, FoodScopeBodyScanner.scan(body));
                        return action.get();
                    });
        } catch (FoodScopeCredentials.Rejected failure) {
            return ResponseEntity.status(failure.status()).build();
        }
    }
}
