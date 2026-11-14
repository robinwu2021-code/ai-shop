package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Publication;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Scope;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Status;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Data;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.StoreMode;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Durable supplier STORE effects use actual HTTP/store identity without publishing an artificial
 * SKU catalog.
 */
class MockStoreBusinessAvailabilityHttpTest {
    @TempDir Path directory;
    final MockCommercePlatformTest fixture = new MockCommercePlatformTest();
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    MockCommercePlatform server() throws Exception {
        fixture.directory = directory;
        return fixture.server();
    }

    void seed(MockCommercePlatform server, MockCommercePlatform.Scenario scenario)
            throws Exception {
        fixture.post(
                server,
                "admin/fixtures",
                new MockCommercePlatform.Fixture(
                        "E",
                        "S",
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        "MOCK:TW",
                        Set.of("AVAILABILITY"),
                        scenario));
    }

    ChannelConnectionIdentityPort.Proof identity(MockCommerceGateway gateway) {
        return gateway.verify(
                new ChannelConnectionIdentityPort.Identity(
                        "E", "S", "ACC", "MOCK_PROFILE", "EA", "ES", "MOCK:TW", false, 0, 0));
    }

    Publication publication(
            String operation,
            long generation,
            State state,
            StoreMode mode,
            String credential,
            ChannelConnectionIdentityPort.Proof proof) {
        return new Publication(
                operation,
                generation,
                new Scope("E", "S", "CON", "MOCK_PROFILE", "EA", "ES", "CR", "AUTH", credential),
                new Identity("ES", List.of()),
                null,
                null,
                new Data(
                        new Query("E", "S", Kind.STORE, "S", Owner.FOOD, mode),
                        state,
                        null,
                        null,
                        "ORIGINAL_STORE:" + state),
                "ORIGINAL_STORE_CALENDAR",
                proof);
    }

    @Test
    void
            persistedStoreEffectRemainsQueryableAfterTimeoutRestartAndGrantRevocationWithoutSkuCatalog()
                    throws Exception {
        Publication sent;
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = fixture.gateway(server);
            String credential = fixture.authorize(gateway);
            var proof = identity(gateway);
            seed(server, MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT);
            sent =
                    publication(
                            "STORE1",
                            1,
                            State.SUSPENDED,
                            StoreMode.ACCEPTING_ORDERS,
                            credential,
                            proof);
            assertThat(gateway.publishAvailability(sent).status()).isEqualTo(Status.UNKNOWN);
            gateway.revoke(
                    new ChannelAuthorizationGateway.RevocationContext(
                            "REVOKE", "E", "A", "MOCK_PROFILE", "EA", "AUTH", credential));
        }
        try (var server = server()) {
            assertThat(fixture.gateway(server).queryAvailability(sent).status())
                    .isEqualTo(Status.CONFIRMED);
            try (var files = Files.list(directory)) {
                assertThat(
                                files.filter(p -> p.getFileName().toString().startsWith("catalog-"))
                                        .count())
                        .isZero();
            }
        }
    }

    @Test
    void lateOlderOpenCannotOverwriteNewerClosedAndAcceptingModeHasItsOwnStoreGeneration()
            throws Exception {
        try (var server = server()) {
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = fixture.gateway(server);
            String credential = fixture.authorize(gateway);
            var proof = identity(gateway);
            seed(server, MockCommercePlatform.Scenario.UNKNOWN);
            var old =
                    publication(
                            "OLD_OPEN", 1, State.OPEN, StoreMode.PHYSICAL_OPEN, credential, proof);
            assertThat(gateway.publishAvailability(old).status()).isEqualTo(Status.UNKNOWN);
            seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var closed =
                    publication(
                            "NEW_CLOSED",
                            2,
                            State.CLOSED,
                            StoreMode.PHYSICAL_OPEN,
                            credential,
                            proof);
            assertThat(gateway.publishAvailability(closed).status()).isEqualTo(Status.CONFIRMED);
            fixture.post(
                    server,
                    "admin/availability/resolve",
                    new MockCommercePlatform.ResolveAvailability(old, Status.CONFIRMED));
            assertThat(gateway.queryAvailability(old).status()).isEqualTo(Status.REJECTED);
            assertThat(
                            gateway.publishAvailability(
                                            publication(
                                                    "ACCEPTING",
                                                    1,
                                                    State.SUSPENDED,
                                                    StoreMode.ACCEPTING_ORDERS,
                                                    credential,
                                                    proof))
                                    .status())
                    .isEqualTo(Status.CONFIRMED);
            try (var files = Files.list(directory)) {
                var books =
                        files.filter(
                                        p ->
                                                p.getFileName()
                                                        .toString()
                                                        .startsWith("availability-book-"))
                                .toList();
                assertThat(books).hasSize(2);
                assertThat(
                                books.stream()
                                        .map(
                                                p -> {
                                                    try {
                                                        return json.readTree(Files.readString(p))
                                                                .path("effect")
                                                                .path("source")
                                                                .path("state")
                                                                .asText();
                                                    } catch (Exception failure) {
                                                        throw new IllegalStateException(failure);
                                                    }
                                                })
                                        .toList())
                        .containsExactlyInAnyOrder("CLOSED", "SUSPENDED");
            }
        }
    }
}
