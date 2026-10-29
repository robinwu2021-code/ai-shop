package ai.neargo.shop.commerce.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.api.internal.InternalChannelFulfillmentTriggerController;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerInternalApi;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class FulfillmentTriggerSecurityTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final String key = "0123456789abcdef0123456789abcdef";

    String sign(byte[] body, FoodScopeCredentials.Realm realm) {
        return FoodScopeCredentials.sign(
                new FoodScopeCredentials.Claims(
                        realm,
                        realm == FoodScopeCredentials.Realm.STAFF ? "E" : null,
                        realm == FoodScopeCredentials.Realm.STAFF ? List.of("S") : List.of(),
                        realm == FoodScopeCredentials.Realm.STAFF ? "A" : null,
                        now.getEpochSecond() + FoodScopeCredentials.TTL_SECONDS,
                        FoodScopeCredentials.digest(
                                "POST", ChannelFulfillmentTriggerInternalApi.PATH, body)),
                FoodScopeCredentials.key(key));
    }

    @Test
    void originalServiceTokenAndSystemBodyDigestAreBothRequiredBeforeAnyCimIntake() {
        var calls = new AtomicInteger();
        ChannelFulfillmentTriggerPort trigger =
                request -> {
                    calls.incrementAndGet();
                    return new ChannelFulfillmentTriggerPort.Result(
                            request,
                            ChannelFulfillmentTriggerPort.Status.WAITING_IMPORT,
                            null,
                            null);
                };
        var controller =
                new InternalChannelFulfillmentTriggerController(
                        "INTERNAL_TOKEN", key, "", trigger, json, Clock.fixed(now, ZoneOffset.UTC));
        var request =
                new ChannelFulfillmentTriggerPort.Request(
                        "E",
                        "S",
                        Owner.FOOD,
                        "IMPORT",
                        "BATCH",
                        "CORE",
                        "HANDOVER",
                        "CUSTODY_V1:" + "a".repeat(64),
                        "SERVICE_EVENT");
        byte[] body = json.writeValueAsBytes(request);
        String system = sign(body, FoodScopeCredentials.Realm.SYSTEM);
        assertThat(controller.record("WRONG", system, body).getStatusCode().value()).isEqualTo(401);
        assertThat(
                        controller
                                .record(
                                        "INTERNAL_TOKEN",
                                        sign(body, FoodScopeCredentials.Realm.STAFF),
                                        body)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        byte[] modified =
                json.writeValueAsBytes(
                        new ChannelFulfillmentTriggerPort.Request(
                                "E",
                                "OTHER",
                                Owner.FOOD,
                                "IMPORT",
                                "BATCH",
                                "CORE",
                                "HANDOVER",
                                request.sourceRevision(),
                                "SERVICE_EVENT"));
        assertThat(controller.record("INTERNAL_TOKEN", system, modified).getStatusCode().value())
                .isEqualTo(401);
        assertThat(calls).hasValue(0);
        assertThat(controller.record("INTERNAL_TOKEN", system, body).getStatusCode().value())
                .isEqualTo(200);
        assertThat(calls).hasValue(1);
    }
}
