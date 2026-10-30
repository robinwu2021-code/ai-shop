package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.food.commerce.port.FoodChannelSourceFactsPortImpl;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort.Resolution;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class FoodChannelSourceFactsTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final Instant now = FoodCatalogBindingFixtures.NOW;
    final ChannelOrderSourceEvidencePort evidence = mock(ChannelOrderSourceEvidencePort.class);
    final ConfigRevisionService revisions = mock(ConfigRevisionService.class);
    final MaterialCatalogQueryPort materials = mock(MaterialCatalogQueryPort.class);
    final FoodChannelSourceFactsPortImpl provider =
            new FoodChannelSourceFactsPortImpl(
                    evidence, revisions, materials, f.json, Clock.fixed(now, ZoneOffset.UTC));

    ChannelOrderSourceEvidencePort.Evidence source(boolean paid, boolean missingMaterial) {
        var oldProof = f.menu();
        var mq =
                new MenuSourceContracts.Query(
                        "E",
                        "S",
                        "M",
                        "MR",
                        MenuSourceContracts.Kind.MENU,
                        "M",
                        null,
                        null,
                        MenuSourceContracts.Mode.HISTORICAL);
        var unsigned =
                new MenuSourceContracts.Proof(
                        1,
                        mq,
                        oldProof.publication(),
                        MenuSourcePolicy.project(f.json, mq, oldProof.publication()),
                        oldProof.verifiedAt(),
                        oldProof.validUntil(),
                        null);
        var proof =
                new MenuSourceContracts.Proof(
                        1,
                        mq,
                        unsigned.publication(),
                        unsigned.resource(),
                        unsigned.verifiedAt(),
                        unsigned.validUntil(),
                        MenuSourcePolicy.receipt(f.json, unsigned));
        when(revisions.verifyMenuResource(mq)).thenReturn(proof);
        var sourceProof =
                new Validation(
                        Status.VALID,
                        "M",
                        "MR",
                        proof.publication().publication().contentHash(),
                        now.plusSeconds(25),
                        proof.receiptNo());
        var price =
                new ChannelCatalogDetails.UnitPrice(
                        100L, "AED", "FOOD_MENU:ITEM", "ORIGINAL_PRICE_REV", "TAX", "PRICE");
        var variant =
                new ChannelCatalogDetails.Variant(
                        "SKU",
                        "One",
                        price,
                        new ChannelCatalogDetails.Inventory("UNKNOWN", null, null),
                        List.of(),
                        List.of());
        var offer =
                new ChannelCatalogDetails.Offer(
                        "OFFER",
                        "M",
                        "MR",
                        "G",
                        "Goods",
                        "SECTION",
                        List.of(variant),
                        List.of(),
                        "ITEM",
                        null,
                        new ChannelCatalogDetails.Measurement("EA", "COUNT", 0, null));
        String taxHash =
                new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE)
                        .snapshotHash("MERCHANT_CUSTOM:E", 3);
        var tax =
                CatalogPriceSourceFacts.taxFact(
                        new MerchantTaxSnapshot(
                                "MAIN",
                                "E",
                                "S",
                                3,
                                MerchantTaxSnapshot.Source.MERCHANT_CUSTOM,
                                "E",
                                3,
                                500,
                                PriceTaxMode.EXCLUSIVE,
                                taxHash));
        var catalog =
                new ChannelCatalogSourcePort.Document(
                        Owner.FOOD,
                        "AED",
                        List.of(
                                new ChannelCatalogSourcePort.ValidatedSource(
                                        Owner.FOOD, "M", "MR", 0, sourceProof)),
                        List.of(),
                        List.of(),
                        List.of(),
                        now.minusSeconds(300),
                        "ORIGINAL_CATALOG",
                        3,
                        new ChannelCatalogDetails(List.of(), List.of(offer), List.of()),
                        new CatalogAvailability(
                                now.minusSeconds(86400), now.plusSeconds(86400), List.of()),
                        List.of(
                                new CatalogTaxBasis(
                                        "TAX", "E", "S", new Market("AE", "AED", 2), tax)));
        var offerMap = mapping(ObjectType.OFFER, "ITEM", sourceProof.contentHash());
        var skuMap = mapping(ObjectType.SKU, "SKU", sourceProof.contentHash());
        var invoice =
                new ChannelOrderGateway.Line(
                        "LINE",
                        offerMap.identity(),
                        skuMap.identity(),
                        "2",
                        "EA",
                        190,
                        10,
                        9,
                        189,
                        List.of(),
                        "Original note");
        var original =
                new ChannelOrderGateway.Document(
                        1,
                        "REMOTE_ACCOUNT",
                        "REMOTE_STORE",
                        "ORDER",
                        "V1",
                        1L,
                        CatalogDocumentPolicy.hash(catalog, f.json),
                        "AED",
                        ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                        ChannelOrderGateway.PaymentState.UNPAID,
                        null,
                        ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                        now.minusSeconds(600),
                        null,
                        new ChannelOrderGateway.Customer("C", "Recipient", "123", "Address"),
                        List.of(invoice),
                        new ChannelOrderGateway.Totals(0, 0, 0, 0, 189, 189));
        var originalSnapshot =
                new ChannelOrderGateway.Snapshot(
                        original,
                        now.minusSeconds(60),
                        now.minusSeconds(30),
                        "ORIGINAL_READ",
                        PricingSnapshotHash.of(original));
        var command =
                new ChannelOrderIngestPort.Command(
                        "IMPORT",
                        "E",
                        "S",
                        "CON",
                        "CR",
                        "SNAPSHOT",
                        originalSnapshot,
                        List.of(
                                new ChannelOrderIngestPort.MappedLine(
                                        "LINE", offerMap, skuMap, "2", "EA", List.of())));
        var currentDoc =
                new ChannelOrderGateway.Document(
                        original.schemaVersion(),
                        original.externalAccountRef(),
                        original.externalStoreRef(),
                        original.externalOrderRef(),
                        "V2",
                        2L,
                        original.catalogHash(),
                        original.currency(),
                        paid ? ChannelOrderGateway.State.ACCEPTED : original.state(),
                        paid
                                ? ChannelOrderGateway.PaymentState.PLATFORM_PAID
                                : original.paymentState(),
                        paid ? "PAYMENT" : null,
                        original.fulfillment(),
                        original.orderedAt(),
                        original.requestedAt(),
                        original.customer(),
                        original.lines(),
                        original.totals());
        var currentSnapshot =
                new ChannelOrderGateway.Snapshot(
                        currentDoc,
                        now,
                        now.plusSeconds(20),
                        "FRESH_ACTUAL_READ",
                        PricingSnapshotHash.of(currentDoc));
        var q =
                new ChannelOrderSourceEvidencePort.Query(
                        "E", "S", "IMPORT", Owner.FOOD, originalSnapshot.documentHash());
        var read =
                new ChannelOrderSourceEvidencePort.Evidence(
                        q,
                        command,
                        currentSnapshot,
                        new ChannelOrderSourceEvidencePort.Routing(
                                "CON", "CR", "TEST", "PROFILE", "a".repeat(64), "AE"),
                        "QUERY_OPERATION",
                        "QUERY_ATTEMPT");
        when(evidence.read(q)).thenReturn(read);
        when(evidence.publishedCatalog(q))
                .thenReturn(
                        new ChannelOrderSourceEvidencePort.PublishedCatalog(
                                q,
                                original.catalogHash(),
                                catalog,
                                "CATALOG_SNAPSHOT",
                                "RUN",
                                "PUBLISH_OPERATION",
                                "PUBLISH_ATTEMPT",
                                "ITEMS_RECEIPT"));
        var material =
                new MaterialCatalogQueryPort.MaterialFact(
                        "MAT",
                        "E",
                        "Goods",
                        "PIECE",
                        0,
                        "OWN",
                        "BASE_ONLY",
                        "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash(
                                "MAT", "E", "Goods", "PIECE", 0, "OWN"));
        when(materials.getSkuMaterials("E", "S", List.of("SKU")))
                .thenReturn(
                        missingMaterial
                                ? List.of()
                                : List.of(
                                        new MaterialCatalogQueryPort.SkuMaterialFact(
                                                "SKU", material)));
        return read;
    }

    Resolution mapping(ObjectType type, String ref, String hash) {
        return new Resolution(
                "MAP" + ref,
                "MAPREV" + ref,
                type,
                new Identity("REMOTE_" + ref, List.of()),
                Owner.FOOD,
                ref,
                "MR",
                "ITEM",
                hash,
                "ORIGINAL_MAPPING",
                "FRESH_MAPPING",
                new SourceContext("M", null, ReadMode.HISTORICAL));
    }

    Key key(ChannelOrderSourceEvidencePort.Evidence read) {
        return new Key("E", "S", "IMPORT", read.original().original().documentHash());
    }

    @Test
    void originalPublicationAndInvoiceSurviveExpirationAndCurrentPaymentRevision() {
        var source = source(true, false);
        var facts = provider.read(key(source));
        assertThat(facts.original().lines()).hasSize(1);
        var component = facts.original().lines().getFirst().components().getFirst();
        assertThat(component.unit()).isEqualTo("PIECE");
        assertThat(component.physicalQuantity()).isEqualByComparingTo("2");
        assertThat(component.grossExTaxMinor()).isEqualTo(190);
        assertThat(component.taxMinor()).isEqualTo(9);
        assertThat(component.netMinor()).isEqualTo(189);
        assertThat(facts.original().financialHash())
                .isEqualTo(facts.current().financialHash())
                .isEqualTo(
                        ChannelOrderEconomicPolicy.fingerprint(
                                source.original().original().document()));
        assertThat(facts.current().collectedMinor()).isEqualTo(189);
        assertThat(facts.current().originalAcceptedAt()).isNull();
        assertThat(facts.original().catalogPublicationReceiptRef()).isEqualTo("ITEMS_RECEIPT");
    }

    @Test
    void currentReadDoesNotLoadHistoricalCatalogOrCompileOriginalAgain() {
        var source = source(true, false);
        var facts = provider.current(key(source));
        assertThat(facts.current().queryOperationNo()).isEqualTo("QUERY_OPERATION");
        assertThat(facts.sourceReceiptRef()).isEqualTo("FRESH_ACTUAL_READ");
        verify(evidence, never()).publishedCatalog(any());
        verifyNoInteractions(revisions, materials);
    }

    @Test
    void missingActualFinishedMaterialCannotBecomeAnUnlimitedOrNoStockComponent() {
        var source = source(false, true);
        assertThatThrownBy(() -> provider.read(key(source)))
                .isInstanceOf(IllegalStateException.class);
    }
}
