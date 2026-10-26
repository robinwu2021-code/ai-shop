package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Account;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Connection;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Grant;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityEvidence;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Profile;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Provider;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelCatalogAuthorityInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RemoteChannelCatalogAuthorityServiceTest {
    static final Query QUERY = new Query("E", "S", "CON", "CR");
    static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    Facts facts() {
        var f =
                new Facts(
                        1,
                        "CATALOG_AUTHORITY",
                        QUERY,
                        new Provider("P", "TEST", 0L),
                        new Profile("PP", "DELIVERY", "TW", "TEST", "test:TW", false, 0L),
                        new Account("ACC", "REMOTE_ACCOUNT", 0L),
                        new Connection(
                                "CON",
                                "CR",
                                "REMOTE_STORE",
                                ChannelIdentity.storeKey(
                                        "test:TW", false, "REMOTE_ACCOUNT", "REMOTE_STORE"),
                                "FOOD",
                                "LOCAL",
                                "INSTANT",
                                "a".repeat(64),
                                0L,
                                0L,
                                1L),
                        new Grant("AUTH", 0L, 1L, "CATALOG", "b".repeat(64), NOW.plusSeconds(60)),
                        null,
                        NOW,
                        NOW.plusSeconds(30),
                        null);
        return ChannelCatalogAuthorityPolicy.seal(
                new Facts(
                        1,
                        f.purpose(),
                        QUERY,
                        f.provider(),
                        f.profile(),
                        f.account(),
                        f.connection(),
                        f.grant(),
                        new IdentityEvidence(
                                "VERIFIED",
                                PricingSnapshotHash.of(ChannelCatalogAuthorityPolicy.identity(f)),
                                "ORIGINAL",
                                NOW,
                                NOW.plusSeconds(60)),
                        NOW,
                        NOW.plusSeconds(30),
                        null));
    }

    @Test
    void typedFactsRejectTamperingMissingFieldsAndPreserveFailures() {
        var api = mock(ChannelCatalogAuthorityInternalApi.class);
        var source = new RemoteChannelCatalogAuthorityService(api, CLOCK);
        when(api.capture(QUERY)).thenReturn(facts());
        assertThat(source.capture(QUERY).query()).isEqualTo(QUERY);
        var f = facts();
        when(api.capture(QUERY))
                .thenReturn(
                        new Facts(
                                1,
                                f.purpose(),
                                QUERY,
                                f.provider(),
                                f.profile(),
                                f.account(),
                                f.connection(),
                                f.grant(),
                                f.identity(),
                                f.verifiedAt(),
                                f.validUntil(),
                                "a".repeat(64)));
        assertThatThrownBy(() -> source.capture(QUERY)).isInstanceOf(IllegalStateException.class);
        when(api.capture(QUERY)).thenReturn(null);
        assertThatThrownBy(() -> source.capture(QUERY)).isInstanceOf(IllegalStateException.class);
        for (int status : List.of(400, 403, 404, 409, 501, 503)) {
            when(api.capture(QUERY))
                    .thenThrow(
                            new ServiceCallException(
                                    CallOutcome.REMOTE_ERROR,
                                    ServiceName.COMMERCE,
                                    status,
                                    "safe"));
            assertThatThrownBy(() -> source.capture(QUERY))
                    .isInstanceOf(status < 500 ? BizException.class : ServiceCallException.class);
            reset(api);
        }
    }

    @Test
    void originalEvidenceAndStrictTypesSurviveActualAuthenticatedHttp() throws Exception {
        var valid = json.writeValueAsString(facts());
        var response = new AtomicReference<>(valid);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ChannelCatalogAuthorityInternalApi.CAPTURE_PATH,
                ex -> {
                    calls.incrementAndGet();
                    assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                            .isEqualTo("wire");
                    assertThat(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8))
                            .contains("\"connectionNo\":\"CON\"")
                            .doesNotContain("credential", "price");
                    var bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(CommerceAuthorityClientAutoConfiguration.class))
                    .withPropertyValues(
                            "shop.commerce.authority-client.enabled=true",
                            "shop.services.internal-token=wire",
                            "shop.services.targets.COMMERCE=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            c -> {
                                assertThat(c).hasNotFailed();
                                var api = c.getBean(ChannelCatalogAuthorityInternalApi.class);
                                var source = new RemoteChannelCatalogAuthorityService(api, CLOCK);
                                assertThat(source.capture(QUERY)).isEqualTo(facts());
                                for (var bad :
                                        List.of(
                                                valid.replace("\"version\":0", "\"version\":\"0\""),
                                                valid.replace(
                                                        "\"accountScoped\":false",
                                                        "\"accountScoped\":0"),
                                                valid.replace("\"market\":\"TW\"", "\"market\":42"),
                                                valid.substring(0, valid.length() - 1)
                                                        + ",\"credentialRef\":\"secret\"}",
                                                valid.replace(
                                                        "\"sourceOwner\":\"FOOD\"",
                                                        "\"sourceOwner\":\"RETAIL\""))) {
                                    response.set(bad);
                                    assertThatThrownBy(() -> source.capture(QUERY))
                                            .isInstanceOf(RuntimeException.class);
                                }
                            });
            assertThat(calls).hasValue(6);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteReadRequiresExplicitOptInAndOriginalLocalOwnerWins() {
        var runner =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(
                                        CommerceAuthorityClientAutoConfiguration.class));
        runner.run(c -> assertThat(c).doesNotHaveBean(ChannelCatalogAuthorityService.class));
        var local = mock(ChannelCatalogAuthorityService.class);
        runner.withBean(ChannelCatalogAuthorityService.class, () -> local)
                .withPropertyValues("shop.commerce.authority-client.enabled=true")
                .run(
                        c -> {
                            assertThat(c.getBean(ChannelCatalogAuthorityService.class))
                                    .isSameAs(local);
                            assertThat(c).doesNotHaveBean(ChannelCatalogAuthorityInternalApi.class);
                        });
    }
}
