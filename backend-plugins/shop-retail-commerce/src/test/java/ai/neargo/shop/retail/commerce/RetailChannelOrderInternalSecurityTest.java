package ai.neargo.shop.retail.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.retail.commerce.api.internal.RetailChannelOrderInternalSecurity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class RetailChannelOrderInternalSecurityTest {
    final Instant now = Instant.parse("2026-10-29T04:00:00Z");
    final String key = "retail-original-owner-key-12345678901234567890";
    final String path = "/internal/retail/channel-orders/get";
    final byte[] body = "{\"entityNo\":\"E\",\"storeNo\":\"S\"}".getBytes(StandardCharsets.UTF_8);
    final RetailChannelOrderInternalSecurity security =
            new RetailChannelOrderInternalSecurity(
                    "TOKEN", key, null, Clock.fixed(now, ZoneOffset.UTC));

    String signature(String route, byte[] value, FoodScopeCredentials.Realm realm) {
        return FoodScopeCredentials.sign(
                new FoodScopeCredentials.Claims(
                        realm,
                        "E",
                        List.of("S"),
                        "ACTOR",
                        now.getEpochSecond() + 20,
                        FoodScopeCredentials.digest("POST", route, value)),
                FoodScopeCredentials.key(key));
    }

    @Test
    void serviceTokenIsVerifiedBeforeParsingOrCallingOriginalOwner() {
        var called = new AtomicBoolean();
        var response =
                security.invoke(
                        "WRONG",
                        "malformed",
                        path,
                        new byte[] {0},
                        () -> {
                            called.set(true);
                            return true;
                        });
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(called).isFalse();
    }

    @Test
    void exactOriginalEntityStoreBodyAndRetailPathDigestAreRequired() {
        var called = new AtomicBoolean();
        var signature = signature(path, body, FoodScopeCredentials.Realm.SYSTEM);
        assertThat(
                        security.invoke(
                                        "TOKEN",
                                        signature,
                                        path,
                                        body,
                                        () -> {
                                            called.set(true);
                                            return true;
                                        })
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(called).isTrue();
        var changed = "{\"entityNo\":\"E\",\"storeNo\":\"OTHER\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(
                        security.invoke("TOKEN", signature, path, changed, () -> true)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        security.invoke(
                                        "TOKEN",
                                        signature,
                                        "/internal/food/channel-orders/get",
                                        body,
                                        () -> true)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void staffRealmCannotActAsOriginalRetailSourceOwnerAndAbsentKeysFailClosed() {
        assertThat(
                        security.invoke(
                                        "TOKEN",
                                        signature(path, body, FoodScopeCredentials.Realm.STAFF),
                                        path,
                                        body,
                                        () -> true)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        var missing =
                new RetailChannelOrderInternalSecurity(
                        "TOKEN", null, null, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(
                        missing.invoke(
                                        "TOKEN",
                                        signature(path, body, FoodScopeCredentials.Realm.SYSTEM),
                                        path,
                                        body,
                                        () -> true)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }
}
