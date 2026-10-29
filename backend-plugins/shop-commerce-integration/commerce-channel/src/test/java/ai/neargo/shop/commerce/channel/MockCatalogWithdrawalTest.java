package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogReplacement;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

class MockCatalogWithdrawalTest {
    @TempDir Path directory;
    final Instant now = Instant.now();
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    ChannelCatalogAuthorityContracts.Facts authority(String store) {
        var query = new ChannelCatalogAuthorityContracts.Query("E", store, "CONORIGINAL", "CR");
        var raw =
                new ChannelCatalogAuthorityContracts.Facts(
                        1,
                        "CATALOG_AUTHORITY",
                        query,
                        new ChannelCatalogAuthorityContracts.Provider("P", "MOCK", 0L),
                        new ChannelCatalogAuthorityContracts.Profile(
                                "PP", "DELIVERY", "CN", "MOCK", "MOCK:CN", false, 0L),
                        new ChannelCatalogAuthorityContracts.Account("A", "EA", 0L),
                        new ChannelCatalogAuthorityContracts.Connection(
                                "CONORIGINAL",
                                "CR",
                                "ES",
                                ChannelIdentity.storeKey("MOCK:CN", false, "EA", "ES"),
                                "FOOD",
                                "LOCAL",
                                "INSTANT",
                                "a".repeat(64),
                                0L,
                                0L,
                                1L),
                        new ChannelCatalogAuthorityContracts.Grant(
                                "AUTH", 0L, 1L, "CATALOG", "b".repeat(64), now.plusSeconds(60)),
                        null,
                        now,
                        now.plusSeconds(25),
                        null);
        var identity =
                new ChannelCatalogAuthorityContracts.IdentityEvidence(
                        "VERIFIED",
                        PricingSnapshotHash.of(ChannelCatalogAuthorityPolicy.identity(raw)),
                        "OWNER_IDENTITY",
                        now,
                        now.plusSeconds(60));
        return ChannelCatalogAuthorityPolicy.seal(
                new ChannelCatalogAuthorityContracts.Facts(
                        1,
                        raw.purpose(),
                        query,
                        raw.provider(),
                        raw.profile(),
                        raw.account(),
                        raw.connection(),
                        raw.grant(),
                        identity,
                        now,
                        raw.validUntil(),
                        null));
    }

    CatalogPriceSourcePort.ScopeFacts scope() {
        var tax =
                new CatalogPriceSourcePort.TaxFact(
                        "MERCHANT_CUSTOM",
                        "E",
                        1L,
                        1L,
                        500,
                        "EXCLUSIVE",
                        new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE)
                                .snapshotHash("MERCHANT_CUSTOM:E", 1));
        return CatalogPriceSourceFacts.sealScope(
                new CatalogPriceSourcePort.ScopeFacts(
                        1,
                        "CATALOG_SCOPE",
                        new CatalogPriceSourcePort.MarketScope("E", "S"),
                        new CatalogPriceSourcePort.Market("CN", "CNY", 2),
                        tax,
                        now,
                        now.plusSeconds(25),
                        null));
    }

    ChannelSourceWithdrawalProvider.Facts withdrawal() {
        var query = new ChannelSourceWithdrawalProvider.Query("E", "S", Owner.FOOD, "MENU", "MR");
        var data =
                new ChannelSourceWithdrawalProvider.Data(
                        query,
                        "DISABLED:2:ACTION",
                        "c".repeat(64),
                        "ACTION",
                        "d".repeat(64),
                        now.minusSeconds(1));
        return new ChannelSourceWithdrawalProvider.Facts(
                data,
                PricingSnapshotHash.of(data),
                now,
                now.plusSeconds(25),
                "ACTUAL_MENU_DISABLED");
    }

    ChannelCatalogSourcePort.Document clear(
            String authorityStore, List<ChannelSourceWithdrawalProvider.Facts> proofs) {
        var scope = scope();
        var tax = new CatalogTaxBasis("TAX", "E", "S", scope.market(), scope.tax());
        var start = now.truncatedTo(ChronoUnit.DAYS);
        return new ChannelCatalogSourcePort.Document(
                Owner.FOOD,
                "CNY",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                now.plusSeconds(20),
                "ACTUAL_OWNER_CLEAR",
                3,
                new ChannelCatalogDetails(List.of(), List.of(), List.of()),
                new CatalogAvailability(start, start.plus(7, ChronoUnit.DAYS), List.of()),
                List.of(tax),
                new CatalogReplacement(scope, authority(authorityStore), proofs));
    }

    ChannelCatalogGateway.Publication clearing(
            ChannelCatalogGateway.Publication original, String operation) {
        var document = clear("S", List.of(withdrawal()));
        return new ChannelCatalogGateway.Publication(
                operation,
                original.generation() + 1,
                original.entityNo(),
                original.storeNo(),
                original.connectionNo(),
                original.externalStoreRef(),
                original.remoteScopeKey(),
                original.authorizationNo(),
                original.credentialRef(),
                CatalogDocumentPolicy.hash(document, json),
                document);
    }

    @Test
    void confirmedWholeScopeEmptyReplacementRemovesCurrentRemoteObjectsAndKeepsOldOperationQuery()
            throws Exception {
        var fixture = new MockCommercePlatformTest();
        fixture.directory = directory;
        try (var server = fixture.server()) {
            fixture.seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var original = fixture.publication("ORIGINAL", credential);
            var result = gateway.publish(original);
            var menu =
                    result.items().stream()
                            .filter(i -> i.objectType().equals("MENU"))
                            .findFirst()
                            .orElseThrow();
            var query =
                    new ChannelResourceIdentityGateway.Query(
                            "CHECK",
                            "E",
                            "S",
                            original.connectionNo(),
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "AUTH",
                            credential,
                            ChannelResourceIdentityGateway.ObjectType.MENU,
                            new ChannelResourceIdentityGateway.Identity(
                                    menu.externalRef(), List.of()));
            assertThat(gateway.query(query).status())
                    .isEqualTo(ChannelResourceIdentityGateway.Status.VERIFIED);
            assertThat(gateway.supportsEmptyReplacement()).isTrue();
            assertThat(gateway.supportsValidatedWithdrawal()).isTrue();
            assertThat(gateway.publish(clearing(original, "CLEAR")).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
            assertThat(gateway.query(query).status())
                    .isEqualTo(ChannelResourceIdentityGateway.Status.NOT_FOUND);
            assertThat(gateway.query(original, original.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.SUCCEEDED);
        }
    }

    @Test
    void unknownEmptyReplacementDoesNotGuessRemoteRemoval() throws Exception {
        var fixture = new MockCommercePlatformTest();
        fixture.directory = directory;
        try (var server = fixture.server()) {
            fixture.seed(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = fixture.gateway(server);
            var credential = fixture.authorize(gateway);
            var original = fixture.publication("ORIGINAL", credential);
            var result = gateway.publish(original);
            var menu =
                    result.items().stream()
                            .filter(i -> i.objectType().equals("MENU"))
                            .findFirst()
                            .orElseThrow();
            fixture.seed(server, MockCommercePlatform.Scenario.UNKNOWN);
            var pending = clearing(original, "UNKNOWN_CLEAR");
            assertThat(gateway.publish(pending).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.UNKNOWN);
            var query =
                    new ChannelResourceIdentityGateway.Query(
                            "CHECK",
                            "E",
                            "S",
                            original.connectionNo(),
                            "MOCK_PROFILE",
                            "EA",
                            "ES",
                            "AUTH",
                            credential,
                            ChannelResourceIdentityGateway.ObjectType.MENU,
                            new ChannelResourceIdentityGateway.Identity(
                                    menu.externalRef(), List.of()));
            assertThat(gateway.query(query).status())
                    .isEqualTo(ChannelResourceIdentityGateway.Status.VERIFIED);
            assertThat(gateway.query(pending, pending.operationNo()).outcome())
                    .isEqualTo(ChannelCatalogGateway.Outcome.UNKNOWN);
        }
    }
}
