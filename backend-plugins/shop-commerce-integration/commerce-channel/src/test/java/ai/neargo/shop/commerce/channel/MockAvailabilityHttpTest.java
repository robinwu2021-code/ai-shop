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
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Persistent external effects cross actual HTTP; no production supplier and no repeated writes
 * after timeout.
 */
class MockAvailabilityHttpTest {
    @TempDir Path directory;
    final MockCommercePlatformTest fixture = new MockCommercePlatformTest();

    MockCommercePlatform server() throws Exception {
        fixture.directory = directory;
        return fixture.server();
    }

    void seed(
            MockCommercePlatform server, MockCommercePlatform.Scenario scenario, boolean permitted)
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
                        permitted ? Set.of("CATALOG", "AVAILABILITY") : Set.of("CATALOG"),
                        scenario));
    }

    record Original(
            MockCommerceGateway gateway,
            String credential,
            ChannelCatalogGateway.Publication catalog,
            ChannelResourceIdentityGateway.Identity identity) {}

    Original original(MockCommercePlatform server) throws Exception {
        seed(server, MockCommercePlatform.Scenario.SUCCESS, true);
        var gateway = fixture.gateway(server);
        var credential = fixture.authorize(gateway);
        var catalog = fixture.publication("CATALOG", credential);
        var result = gateway.publish(catalog);
        assertThat(result.outcome()).isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
        var sku =
                result.items().stream()
                        .filter(i -> "SKU".equals(i.objectType()))
                        .findFirst()
                        .orElseThrow();
        var offer =
                result.items().stream()
                        .filter(i -> "OFFER".equals(i.objectType()))
                        .findFirst()
                        .orElseThrow();
        return new Original(
                gateway,
                credential,
                catalog,
                new ChannelResourceIdentityGateway.Identity(
                        sku.externalRef(),
                        List.of(
                                new ChannelResourceIdentityGateway.Parent(
                                        ChannelResourceIdentityGateway.ObjectType.OFFER,
                                        offer.externalRef()))));
    }

    Publication publication(Original original, String operation, long generation, String quantity) {
        return new Publication(
                operation,
                generation,
                new Scope(
                        "E",
                        "S",
                        original.catalog().connectionNo(),
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        "CR",
                        "AUTH",
                        original.credential()),
                original.identity(),
                "MAP",
                "MR",
                new Data(
                        new Query("E", "S", Kind.STOCK, "SKU"),
                        State.QUANTITY,
                        quantity,
                        "KG",
                        "R" + generation),
                "ORIGINAL_INVENTORY");
    }

    @Test
    void persistentTimeoutIsQueryableAfterRestartAndOriginalGrantRevocation() throws Exception {
        Publication sent;
        try (var server = server()) {
            var original = original(server);
            seed(server, MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT, true);
            sent = publication(original, "AV1", 1, "1.25");
            assertThat(original.gateway().publishAvailability(sent).status())
                    .isEqualTo(Status.UNKNOWN);
            original.gateway()
                    .revoke(
                            new ChannelAuthorizationGateway.RevocationContext(
                                    "REVOKE",
                                    "E",
                                    "A",
                                    "MOCK_PROFILE",
                                    "EA",
                                    "AUTH",
                                    original.credential()));
        }
        try (var server = server()) {
            var gateway = fixture.gateway(server);
            var result = gateway.queryAvailability(sent);
            assertThat(result.status()).isEqualTo(Status.CONFIRMED);
            assertThat(result.operationNo()).isEqualTo(sent.operationNo());
            assertThat(result.publicationHash())
                    .isEqualTo(ai.neargo.shop.common.pricing.PricingSnapshotHash.of(sent));
            var forged =
                    new Publication(
                            sent.operationNo(),
                            sent.generation(),
                            sent.scope(),
                            sent.identity(),
                            sent.mappingNo(),
                            sent.mappingRevisionNo(),
                            new Data(
                                    sent.source().query(),
                                    State.QUANTITY,
                                    "99",
                                    "KG",
                                    sent.source().sourceRevision()),
                            sent.sourceReceiptRef());
            assertThat(gateway.queryAvailability(forged).status()).isEqualTo(Status.UNKNOWN);
            assertThat(gateway.queryAvailability(sent)).isEqualTo(result);
        }
    }

    @Test
    void lateUnknownResolutionCannotRegressAnAcceptedNewerGeneration() throws Exception {
        try (var server = server()) {
            var original = original(server);
            seed(server, MockCommercePlatform.Scenario.UNKNOWN, true);
            var old = publication(original, "OLD", 1, "1");
            assertThat(original.gateway().publishAvailability(old).status())
                    .isEqualTo(Status.UNKNOWN);
            seed(server, MockCommercePlatform.Scenario.SUCCESS, true);
            var fresh = publication(original, "NEW", 2, "2");
            assertThat(original.gateway().publishAvailability(fresh).status())
                    .isEqualTo(Status.CONFIRMED);
            fixture.post(
                    server,
                    "admin/availability/resolve",
                    new MockCommercePlatform.ResolveAvailability(old, Status.CONFIRMED));
            assertThat(original.gateway().queryAvailability(old).status())
                    .isEqualTo(Status.REJECTED);
            assertThat(original.gateway().queryAvailability(fresh).status())
                    .isEqualTo(Status.CONFIRMED);
            try (var files = Files.list(directory)) {
                var book =
                        files.filter(
                                        f ->
                                                f.getFileName()
                                                        .toString()
                                                        .startsWith("availability-book-"))
                                .findFirst()
                                .orElseThrow();
                var state = fixture.json.readTree(Files.readAllBytes(book));
                assertThat(state.get("effect").get("operationNo").asString()).isEqualTo("NEW");
                assertThat(state.get("generation").asLong()).isEqualTo(2);
            }
        }
    }

    @Test
    void originalGrantAndExactCatalogResourceIdentityAreRequiredForTheFirstSend() throws Exception {
        try (var server = server()) {
            var original = original(server);
            var p = publication(original, "BAD_IDENTITY", 1, "3");
            var bad =
                    new Publication(
                            p.operationNo(),
                            p.generation(),
                            p.scope(),
                            new ChannelResourceIdentityGateway.Identity(
                                    p.identity().externalRef(), List.of()),
                            p.mappingNo(),
                            p.mappingRevisionNo(),
                            p.source(),
                            p.sourceReceiptRef());
            assertThat(original.gateway().publishAvailability(bad).status())
                    .isEqualTo(Status.UNKNOWN);
            assertThat(original.gateway().queryAvailability(bad).status())
                    .isEqualTo(Status.UNKNOWN);
            original.gateway()
                    .revoke(
                            new ChannelAuthorizationGateway.RevocationContext(
                                    "REVOKE",
                                    "E",
                                    "A",
                                    "MOCK_PROFILE",
                                    "EA",
                                    "AUTH",
                                    original.credential()));
            assertThat(original.gateway().publishAvailability(p).status())
                    .isEqualTo(Status.UNKNOWN);
            try (var files = Files.list(directory)) {
                assertThat(
                                files.filter(
                                                f ->
                                                        f.getFileName()
                                                                .toString()
                                                                .startsWith("availability-book-"))
                                        .count())
                        .isZero();
            }
        }
    }
}
