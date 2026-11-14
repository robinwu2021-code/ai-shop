package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogReplacement;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

class CatalogReplacementPolicyTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    ChannelCatalogAuthorityContracts.Facts authority(String store) {
        var query = new ChannelCatalogAuthorityContracts.Query("E", store, "CON", "CR");
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
                                "CON",
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

    @Test
    void explicitRealWithdrawalAndScopedTaxCanFreezeAnActuallyEmptyWholeScope() {
        var document = clear("S", List.of(withdrawal()));
        var frozen = CatalogDocumentPolicy.freezeAt(document, List.of(), json, now);
        assertThat(CatalogDocumentPolicy.itemCount(frozen)).isZero();
        assertThat(frozen.taxBases()).hasSize(1);
        assertThat(CatalogResourceIndex.resources(frozen)).isEmpty();
        assertThat(frozen.availability().sources()).isEmpty();
    }

    @Test
    void missingWithdrawalOrWrongOriginalStoreNeverAuthorizesEmpty() {
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freezeAt(
                                        clear("S", List.of()), List.of(), json, now))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freezeAt(
                                        clear("FOREIGN", List.of(withdrawal())),
                                        List.of(),
                                        json,
                                        now))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void forgedOriginalActionOrExpiredWithdrawalCannotClear() {
        var proof = withdrawal();
        var forged =
                new ChannelSourceWithdrawalProvider.Facts(
                        proof.data(),
                        "e".repeat(64),
                        proof.verifiedAt(),
                        proof.validUntil(),
                        proof.ownerReceiptNo());
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freezeAt(
                                        clear("S", List.of(forged)), List.of(), json, now))
                .isInstanceOf(RuntimeException.class);
        var expired =
                new ChannelSourceWithdrawalProvider.Facts(
                        proof.data(),
                        proof.contentHash(),
                        proof.verifiedAt(),
                        now,
                        proof.ownerReceiptNo());
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freezeAt(
                                        clear("S", List.of(expired)), List.of(), json, now))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void replacingTransportReceiptsNeverChangesBusinessHashButRealWithdrawalRevisionDoes() {
        var first = clear("S", List.of(withdrawal()));
        var proof = withdrawal();
        var refreshed =
                new ChannelSourceWithdrawalProvider.Facts(
                        proof.data(),
                        proof.contentHash(),
                        proof.verifiedAt(),
                        proof.validUntil(),
                        "REFRESHED_NATIVE_RECEIPT");
        assertThat(CatalogDocumentPolicy.hash(first, json))
                .isEqualTo(CatalogDocumentPolicy.hash(clear("S", List.of(refreshed)), json));
        var changed =
                new ChannelSourceWithdrawalProvider.Data(
                        proof.data().query(),
                        "DISABLED:3:NEW",
                        proof.data().previousContentHash(),
                        "NEW",
                        proof.data().originalActionHash(),
                        proof.data().withdrawnAt());
        var next =
                new ChannelSourceWithdrawalProvider.Facts(
                        changed,
                        PricingSnapshotHash.of(changed),
                        now,
                        now.plusSeconds(25),
                        "NEW_NATIVE_RECEIPT");
        assertThat(CatalogDocumentPolicy.hash(first, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(clear("S", List.of(next)), json));
    }

    @Test
    void requestedRemovalProofCannotBeDroppedByAnAdapter() {
        var command =
                new ChannelCatalogSourcePort.CaptureCommand(
                        "E", "S", Owner.FOOD, "CON", "CR", List.of(), List.of(withdrawal()));
        var substituted = clear("S", List.of(withdrawal()));
        var actual = substituted.replacement().withdrawals().getFirst();
        var altered =
                new ChannelSourceWithdrawalProvider.Data(
                        actual.data().query(),
                        "DISABLED:3:OTHER",
                        actual.data().previousContentHash(),
                        "OTHER",
                        actual.data().originalActionHash(),
                        actual.data().withdrawnAt());
        var proof =
                new ChannelSourceWithdrawalProvider.Facts(
                        altered,
                        PricingSnapshotHash.of(altered),
                        now,
                        now.plusSeconds(25),
                        "ANOTHER_NATIVE_ACTION");
        assertThatThrownBy(
                        () ->
                                CatalogSourceTransportPolicy.validateDocument(
                                        command,
                                        clear("S", List.of(proof)),
                                        json,
                                        Clock.fixed(now, ZoneOffset.UTC)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void ordinarySchemaSupportDoesNotGrantEmptyOrWithdrawalProtocolCapability() {
        ChannelCatalogGateway legacy =
                new ChannelCatalogGateway() {
                    public String profileNo() {
                        return "LEGACY";
                    }

                    public Set<Capability> capabilities() {
                        return Set.of(Capability.CATALOG);
                    }

                    public boolean supportsDocumentSchema(int version) {
                        return true;
                    }

                    public boolean supportsQuery() {
                        return true;
                    }

                    public Result publish(Publication publication) {
                        throw new UnsupportedOperationException();
                    }

                    public Result query(Publication publication, String task) {
                        throw new UnsupportedOperationException();
                    }
                };
        assertThat(legacy.supportsDocumentSchema(3)).isTrue();
        assertThat(legacy.supportsEmptyReplacement()).isFalse();
        assertThat(legacy.supportsValidatedWithdrawal()).isFalse();
    }
}
