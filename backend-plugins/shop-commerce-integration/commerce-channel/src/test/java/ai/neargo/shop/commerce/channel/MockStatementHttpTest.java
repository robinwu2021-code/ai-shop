package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Breakdown;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Line;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Lookup;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Scope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

class MockStatementHttpTest {
    @TempDir Path directory;
    final MockCommercePlatformTest fixture = new MockCommercePlatformTest();

    MockCommercePlatform server() throws Exception {
        fixture.directory = directory;
        return fixture.server();
    }

    void seed(MockCommercePlatform server) throws Exception {
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
                        Set.of("CATALOG", "STATEMENT"),
                        MockCommercePlatform.Scenario.SUCCESS));
    }

    Document document(String reference) {
        var now = Instant.now();
        var amount = new Breakdown("100", "0", "5", "0", "0", "0", "105");
        return new Document(
                reference,
                "R1",
                "MOCK_PROFILE",
                "EA",
                "CNY",
                now.minusSeconds(86400),
                now.minusSeconds(2),
                now,
                amount,
                List.of(new Line("ORIGINAL_LINE", "SALE", "ES", "ORDER", null, amount)));
    }

    @Test
    void timeoutReadEvidenceSurvivesRestartAndRevocationWithoutRepeatingASensitiveRead()
            throws Exception {
        Lookup lookup;
        try (var server = server()) {
            seed(server);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var doc = document("STATEMENT");
            fixture.post(
                    server,
                    "admin/statements",
                    new MockCommercePlatform.StatementSeed(
                            "E", doc, MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT));
            lookup =
                    new Lookup(
                            "READ",
                            new Scope("E", "ACCOUNT", "MOCK_PROFILE", "EA", "AUTH", credential),
                            "STATEMENT",
                            "R1",
                            PricingSnapshotHash.of(doc));
            assertThat(gateway.fetchStatement(lookup).status())
                    .isEqualTo(ChannelStatementGateway.Status.UNKNOWN);
            gateway.revoke(
                    new ChannelAuthorizationGateway.RevocationContext(
                            "REVOKE", "E", "A", "MOCK_PROFILE", "EA", "AUTH", credential));
        }
        try (var server = server()) {
            var gateway = fixture.gateway(server);
            var receipt = gateway.queryStatement(lookup);
            assertThat(receipt.status()).isEqualTo(ChannelStatementGateway.Status.FOUND);
            assertThat(receipt.documentHash()).isEqualTo(lookup.documentHash());
            assertThat(gateway.fetchStatement(lookup).status())
                    .isEqualTo(ChannelStatementGateway.Status.UNKNOWN);
            assertThat(gateway.queryStatement(lookup)).isEqualTo(receipt);
        }
    }

    @Test
    void anOldAuthorizedAbsenceNeverReadsANewStatementAfterGrantRevocation() throws Exception {
        try (var server = server()) {
            seed(server);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var doc = document("LATER");
            var lookup =
                    new Lookup(
                            "ABSENT",
                            new Scope("E", "ACCOUNT", "MOCK_PROFILE", "EA", "AUTH", credential),
                            "LATER",
                            "R1",
                            PricingSnapshotHash.of(doc));
            assertThat(gateway.fetchStatement(lookup).status())
                    .isEqualTo(ChannelStatementGateway.Status.UNKNOWN);
            gateway.revoke(
                    new ChannelAuthorizationGateway.RevocationContext(
                            "REVOKE", "E", "A", "MOCK_PROFILE", "EA", "AUTH", credential));
            fixture.post(
                    server,
                    "admin/statements",
                    new MockCommercePlatform.StatementSeed(
                            "E", doc, MockCommercePlatform.Scenario.SUCCESS));
            assertThat(gateway.queryStatement(lookup).status())
                    .isEqualTo(ChannelStatementGateway.Status.UNKNOWN);
            var changed =
                    new Lookup(
                            lookup.operationNo(),
                            lookup.scope(),
                            "DIFFERENT",
                            "R1",
                            lookup.documentHash());
            assertThat(gateway.queryStatement(changed).status())
                    .isEqualTo(ChannelStatementGateway.Status.UNKNOWN);
        }
    }
}
