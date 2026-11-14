package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.util.Map;

/** Physically separate test JVMs must traverse production forward and reverse providers. */
@EnabledIfSystemProperty(named = "beauty.bm04.deployment", matches = "true")
class BeautyBm04StandaloneHttpIT {
    @Test
    void privateServerUsesRealHostOwnersAndHasNoLocalOwnerFallback() throws Exception {
        var fixture = new BeautyBm04EmbeddedHttpIT.Fixture();
        var server =
                fixture.json.readTree(Files.readString(fixture.directory.resolve("server.json")));
        assertThat(fixture.host.path("shape").asString()).isEqualTo("standalone");
        assertThat(fixture.host.path("classpath").asString())
                .doesNotContain("beauty-core", "beauty-server");
        assertThat(server.path("classpath").asString())
                .doesNotContain("shop-app", "shop-core", "shop-merchant");
        assertThat(server.path("checkoutBean").asString()).contains("RemoteCheckoutPort");
        assertThat(server.path("workOrderBean").asString()).contains("WorkOrderServiceImpl");
        fixture.authorization();
        fixture.lifecycle(false);
        var events =
                fixture.json.readTree(
                        Files.readString(fixture.directory.resolve("proxy-events.json")));
        boolean prepare = false, source = false;
        for (var event : events) {
            prepare |=
                    event.path("path").asString().equals("/internal/trade/checkout/bound/prepare");
            source |= event.path("path").asString().equals("/internal/trade/checkout/bound/source");
        }
        assertThat(prepare && source).isTrue();
        fixture.command("proxy", "BLOCK", "platform /");
        try {
            fixture.denied(
                    "merchant",
                    "/biz/x/beauty/arrival-challenges",
                    "owner-blocked",
                    Map.of(),
                    fixture.store);
        } finally {
            fixture.command("proxy", "UNBLOCK", "platform /");
        }
        fixture.post("manager", "/biz/x/beauty/arrival-challenges", "owner-restored", Map.of());
    }

    @Test
    void lostPrepareAndRecoveryResponsesKeepOriginalOperationAndClearContexts() throws Exception {
        new BeautyBm04EmbeddedHttpIT.Fixture().lifecycle(true);
    }
}
