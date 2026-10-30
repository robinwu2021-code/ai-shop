package ai.neargo.shop.retail.commerce;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.retail.commerce.port.RetailChannelSourceReader;
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
import ai.neargo.shop.spi.product.RetailProductSourcePort;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Original owner boundaries are test doubles; no doubles replace native source persistence or core
 * receipt conservation.
 */
final class RetailChannelOrderFixtures {
    final Instant now = Instant.parse("2026-10-29T04:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final ChannelOrderSourceEvidencePort evidence = mock(ChannelOrderSourceEvidencePort.class);
    final RetailSelectionMapper selections = mock(RetailSelectionMapper.class);
    final RetailProductSourcePort products = mock(RetailProductSourcePort.class);
    final MaterialCatalogQueryPort materials = mock(MaterialCatalogQueryPort.class);
    final RetailChannelSourceReader reader =
            new RetailChannelSourceReader(evidence, selections, products, materials, json, clock);
    final ChannelOrderIngestPort.Command command;
    final ChannelOrderIngestPort.Reference reference;
    final ChannelOrderSourceEvidencePort.Query query;
    final AtomicReference<ChannelOrderGateway.State> state =
            new AtomicReference<>(ChannelOrderGateway.State.WAITING_ACCEPTANCE);

    RetailChannelOrderFixtures() {
        var selection =
                new RetailSelection(
                        "E",
                        "S",
                        "SEL",
                        "SR",
                        "CON",
                        "CR",
                        List.of(new RetailSelection.Entry("G", 7, "b".repeat(64), List.of("SKU"))));
        when(selections.revision("E", "S", "SEL", "SR")).thenReturn(selection);
        var price =
                new ChannelCatalogDetails.UnitPrice(
                        100L, "AED", "ORIGINAL_PRICE", "PR", "TAX", "PRICE_RECEIPT");
        var variant =
                new ChannelCatalogDetails.Variant(
                        "SKU",
                        "Original SKU",
                        price,
                        new ChannelCatalogDetails.Inventory("UNKNOWN", null, null),
                        List.of(),
                        List.of());
        var offer =
                new ChannelCatalogDetails.Offer(
                        "OFFER",
                        "SEL",
                        "SR",
                        "G",
                        "Original Goods",
                        "SECTION",
                        List.of(variant),
                        List.of(),
                        "G");
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
                                new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE)
                                        .snapshotHash("MERCHANT_CUSTOM:E", 3)));
        var proof =
                new Validation(
                        Status.VALID,
                        "SEL",
                        "SR",
                        selection.contentHash(),
                        now.minusSeconds(60),
                        "SELECTION_READ");
        var catalog =
                new ChannelCatalogSourcePort.Document(
                        Owner.RETAIL,
                        "AED",
                        List.of(
                                new ChannelCatalogSourcePort.ValidatedSource(
                                        Owner.RETAIL, "SEL", "SR", 0, proof)),
                        List.of(),
                        List.of(),
                        List.of(),
                        now.minusSeconds(60),
                        "CATALOG",
                        3,
                        new ChannelCatalogDetails(List.of(), List.of(offer), List.of()),
                        new CatalogAvailability(
                                now.minusSeconds(86400), now.plusSeconds(86400), List.of()),
                        List.of(
                                new CatalogTaxBasis(
                                        "TAX", "E", "S", new Market("AE", "AED", 2), tax)));
        var offerMap = mapping(ObjectType.OFFER, "G", selection.contentHash());
        var skuMap = mapping(ObjectType.SKU, "SKU", selection.contentHash());
        var line =
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
                        "ACCOUNT",
                        "CHANNEL_STORE",
                        "ORDER",
                        "V1",
                        1L,
                        CatalogDocumentPolicy.hash(catalog, json),
                        "AED",
                        ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                        ChannelOrderGateway.PaymentState.UNPAID,
                        null,
                        ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                        now.minusSeconds(600),
                        null,
                        new ChannelOrderGateway.Customer("CUSTOMER", "Recipient", "123", "Address"),
                        List.of(line),
                        new ChannelOrderGateway.Totals(0, 0, 0, 0, 189, 189));
        var snapshot =
                new ChannelOrderGateway.Snapshot(
                        original,
                        now.minusSeconds(120),
                        now.minusSeconds(90),
                        "ORIGINAL_READ",
                        PricingSnapshotHash.of(original));
        command =
                new ChannelOrderIngestPort.Command(
                        "IMPORT",
                        "E",
                        "S",
                        "CON",
                        "CR",
                        "ORIGINAL_SNAPSHOT",
                        snapshot,
                        List.of(
                                new ChannelOrderIngestPort.MappedLine(
                                        "LINE", offerMap, skuMap, "2", "EA", List.of())));
        reference =
                new ChannelOrderIngestPort.Reference("IMPORT", "E", "S", snapshot.documentHash());
        query =
                new ChannelOrderSourceEvidencePort.Query(
                        "E", "S", "IMPORT", Owner.RETAIL, snapshot.documentHash());
        when(evidence.read(query))
                .thenAnswer(
                        a -> {
                            var current =
                                    new ChannelOrderGateway.Document(
                                            original.schemaVersion(),
                                            original.externalAccountRef(),
                                            original.externalStoreRef(),
                                            original.externalOrderRef(),
                                            "V2",
                                            2L,
                                            original.catalogHash(),
                                            original.currency(),
                                            state.get(),
                                            ChannelOrderGateway.PaymentState.PLATFORM_PAID,
                                            "ORIGINAL_PAYMENT",
                                            original.fulfillment(),
                                            original.orderedAt(),
                                            original.requestedAt(),
                                            original.customer(),
                                            original.lines(),
                                            original.totals());
                            return new ChannelOrderSourceEvidencePort.Evidence(
                                    query,
                                    command,
                                    new ChannelOrderGateway.Snapshot(
                                            current,
                                            now,
                                            now.plusSeconds(20),
                                            "ACTUAL_FRESH_READ",
                                            PricingSnapshotHash.of(current)),
                                    new ChannelOrderSourceEvidencePort.Routing(
                                            "CON", "CR", "TEST", "PROFILE", "a".repeat(64), "AE"),
                                    "QUERY_OPERATION",
                                    "QUERY_ATTEMPT");
                        });
        when(evidence.publishedCatalog(query))
                .thenReturn(
                        new ChannelOrderSourceEvidencePort.PublishedCatalog(
                                query,
                                original.catalogHash(),
                                catalog,
                                "CATALOG_SNAPSHOT",
                                "PUBLISH_RUN",
                                "PUBLISH_OPERATION",
                                "PUBLISH_ATTEMPT",
                                "ITEMS_RECEIPT"));
        when(products.read(any()))
                .thenAnswer(
                        a ->
                                new RetailProductSourcePort.Facts(
                                        a.getArgument(0),
                                        7,
                                        "b".repeat(64),
                                        List.of("SKU"),
                                        now,
                                        now.plusSeconds(18),
                                        "HISTORICAL_PRODUCT_READ"));
        var material =
                new MaterialCatalogQueryPort.MaterialFact(
                        "MATERIAL",
                        "E",
                        "Original goods",
                        "PIECE",
                        0,
                        "OWN",
                        "BASE_ONLY",
                        "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash(
                                "MATERIAL", "E", "Original goods", "PIECE", 0, "OWN"));
        when(materials.getSkuMaterials("E", "S", List.of("SKU")))
                .thenReturn(List.of(new MaterialCatalogQueryPort.SkuMaterialFact("SKU", material)));
    }

    Resolution mapping(ObjectType type, String ref, String hash) {
        return new Resolution(
                "MAP_" + ref,
                "MREV_" + ref,
                type,
                new Identity("REMOTE_" + ref, List.of()),
                Owner.RETAIL,
                ref,
                "SR",
                "G",
                hash,
                "ACTUAL_MAPPING",
                "FRESH_MAPPING",
                new SourceContext("SEL", null, ReadMode.HISTORICAL));
    }
}
