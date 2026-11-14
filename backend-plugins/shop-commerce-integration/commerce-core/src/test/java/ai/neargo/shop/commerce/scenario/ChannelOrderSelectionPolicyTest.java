package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderSelectionPolicy;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.BundleGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.BundleMember;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Inventory;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Option;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.OptionGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.QuantityRange;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.SelectionRules;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.UnitPrice;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Variant;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort.Resolution;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Pure selection tests grant no provider, stock or original industry acceptance authority. */
class ChannelOrderSelectionPolicyTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final String sourceHash = "a".repeat(64);
    final Inventory unknown = new Inventory("UNKNOWN", null, null);

    QuantityRange range(String min, String max) {
        return new QuantityRange(min, max, min);
    }

    UnitPrice price(long value) {
        return new UnitPrice(value, "AED", "P", "R", "TAX", "ORIGINAL_PRICE_RECEIPT");
    }

    ChannelCatalogSourcePort.Document catalog() {
        var now = Instant.now();
        var until = now.plusSeconds(25);
        var horizon = now.truncatedTo(ChronoUnit.DAYS);
        var o1 =
                new Option(
                        "O1",
                        "Milk",
                        "MODIFIER",
                        "MOD1",
                        "MILK_SKU",
                        price(25),
                        unknown,
                        range("0", "2"));
        var o2 =
                new Option(
                        "O2",
                        "Soy",
                        "MODIFIER",
                        "MOD2",
                        "SOY_SKU",
                        price(40),
                        unknown,
                        range("0", "2"));
        var o3 =
                new Option(
                        "O3",
                        "Cold",
                        "SPEC_VALUE",
                        "TEMP_COLD",
                        null,
                        price(0),
                        new Inventory("NOT_APPLICABLE", null, null),
                        range("0", "1"));
        var group =
                new OptionGroup(
                        "OG",
                        "Choose",
                        range("0", "3"),
                        List.of(o1, o2, o3),
                        new SelectionRules(
                                List.of(List.of("O1", "O2")),
                                List.of(
                                        new ChannelCatalogDetails.Dependency(
                                                "O1", List.of("O3")))));
        var bundle =
                new BundleMember(
                        "BC",
                        "Side",
                        "SIDE_SKU",
                        "ADD_SKU",
                        price(10),
                        unknown,
                        "2.5",
                        range("1", "2"));
        var variant =
                new Variant(
                        "SKU",
                        "Main",
                        price(100),
                        unknown,
                        List.of(group),
                        List.of(
                                new BundleGroup(
                                        "BG", "Set", range("1", "2"), List.of(bundle), null)));
        var offer =
                new ChannelCatalogDetails.Offer(
                        "OFFER",
                        "MENU",
                        "R1",
                        "GOODS",
                        "Set",
                        "SECTION",
                        List.of(variant),
                        List.of(),
                        "ORIGINAL_OFFER",
                        null,
                        new ChannelCatalogDetails.Measurement("KG", "WEIGHT", 6, null));
        var proof = new Validation(Status.VALID, "MENU", "R1", sourceHash, until, "OWNER_READ");
        var document =
                new ChannelCatalogSourcePort.Document(
                        Owner.FOOD,
                        "AED",
                        List.of(
                                new ChannelCatalogSourcePort.ValidatedSource(
                                        Owner.FOOD, "MENU", "R1", 0, proof)),
                        List.of(),
                        List.of(),
                        List.of(),
                        until,
                        "ORIGINAL_CATALOG",
                        3,
                        new ChannelCatalogDetails(
                                List.of(
                                        new ChannelCatalogSourcePort.Section(
                                                "SECTION",
                                                "Original category",
                                                0,
                                                new ChannelCatalogSourcePort.SectionSource(
                                                        "MENU", "R1", "CATEGORY"))),
                                List.of(offer),
                                List.of()),
                        new CatalogAvailability(
                                horizon,
                                horizon.plus(7, ChronoUnit.DAYS),
                                List.of(
                                        new CatalogAvailability.SourceWindow(
                                                "MENU",
                                                "R1",
                                                List.of(
                                                        new CatalogAvailability.UtcWindow(
                                                                horizon,
                                                                horizon.plus(
                                                                        7, ChronoUnit.DAYS)))))),
                        List.of(
                                new CatalogTaxBasis(
                                        "TAX",
                                        "E",
                                        "S",
                                        new CatalogPriceSourcePort.Market("AE", "AED", 2),
                                        new CatalogPriceSourcePort.TaxFact(
                                                "MERCHANT_CUSTOM",
                                                "E",
                                                1L,
                                                1L,
                                                500,
                                                "EXCLUSIVE",
                                                new TaxSettingDefinition(
                                                                true, 500, PriceTaxMode.EXCLUSIVE)
                                                        .snapshotHash("MERCHANT_CUSTOM:E", 1)))));
        // The fixture must be a complete valid historical v3 document before a selection assertion
        // can run.
        return CatalogDocumentPolicy.freezeAt(document, document.sources(), json, now);
    }

    record Pick(String kind, String choice, String quantity, long extra) {}

    List<Pick> valid() {
        return List.of(
                new Pick("OPTION", "O1", "0.5", 16),
                new Pick("OPTION", "O3", "1", 0),
                new Pick("BUNDLE_MEMBER", "BC", "2", 63));
    }

    Resolution resolution(ObjectType type, String ref, String group) {
        return new Resolution(
                "MAP_" + ref,
                "REV_" + ref,
                type,
                new Identity("REMOTE_" + ref, List.of()),
                Owner.FOOD,
                ref,
                "R1",
                "ORIGINAL_OFFER",
                sourceHash,
                "ORIGINAL_EXTERNAL",
                "OWNER_READ",
                new SourceContext(
                        "MENU",
                        type == ObjectType.OPTION ? group : null,
                        ReadMode.HISTORICAL,
                        type == ObjectType.BUNDLE_MEMBER ? "SKU" : null,
                        type == ObjectType.BUNDLE_MEMBER ? group : null));
    }

    ChannelOrderIngestPort.Command command(
            ChannelCatalogSourcePort.Document catalog, List<Pick> picks) {
        var main = resolution(ObjectType.OFFER, "ORIGINAL_OFFER", null);
        var sku = resolution(ObjectType.SKU, "SKU", null);
        var selections =
                picks.stream()
                        .map(
                                p ->
                                        new ChannelOrderGateway.Selection(
                                                "EXTERNAL_" + p.choice(),
                                                p.kind(),
                                                new Identity("REMOTE_" + p.choice(), List.of()),
                                                p.quantity(),
                                                p.extra()))
                        .toList();
        var line =
                new ChannelOrderGateway.Line(
                        "LINE",
                        main.identity(),
                        sku.identity(),
                        "1.25",
                        "KG",
                        204,
                        0,
                        0,
                        204,
                        selections,
                        null);
        var document =
                new ChannelOrderGateway.Document(
                        1,
                        "ACCOUNT",
                        "STORE",
                        "EXTERNAL",
                        "R1",
                        1L,
                        CatalogDocumentPolicy.hash(catalog, json),
                        "AED",
                        ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                        ChannelOrderGateway.PaymentState.UNPAID,
                        null,
                        ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                        Instant.now().minusSeconds(60),
                        null,
                        null,
                        List.of(line),
                        new ChannelOrderGateway.Totals(0, 0, 0, 0, 204, 204));
        var mappings =
                picks.stream()
                        .map(
                                p ->
                                        new ChannelOrderIngestPort.MappedSelection(
                                                "EXTERNAL_" + p.choice(),
                                                p.kind(),
                                                resolution(
                                                        ObjectType.valueOf(p.kind()),
                                                        p.choice(),
                                                        p.kind().equals("OPTION") ? "OG" : "BG"),
                                                p.quantity(),
                                                p.extra()))
                        .toList();
        var mapped =
                new ChannelOrderIngestPort.MappedLine("LINE", main, sku, "1.25", "KG", mappings);
        var now = Instant.now();
        return new ChannelOrderIngestPort.Command(
                "IMPORT",
                "E",
                "S",
                "CON",
                "CR",
                "REV",
                new ChannelOrderGateway.Snapshot(
                        document,
                        now,
                        now.plusSeconds(25),
                        "PLATFORM_READ",
                        PricingSnapshotHash.of(document)),
                List.of(mapped));
    }

    @Test
    void optionsAndBundlePhysicalQuantitiesUseOriginalMultipliersWithoutCurrentStockOrPrices() {
        var catalog = catalog();
        var result =
                ChannelOrderSelectionPolicy.validate(command(catalog, valid()), catalog).getFirst();
        assertThat(result.physicalQuantity()).isEqualByComparingTo("1.25");
        assertThat(result.selections().getFirst().physicalQuantity()).isEqualByComparingTo("0.625");
        assertThat(result.selections().getLast().physicalQuantity()).isEqualByComparingTo("6.25");
        assertThat(result.selections().get(1).skuRef())
                .isNull(); // a taste does not invent a stock SKU
    }

    @Test
    void requiredGroupsDependenciesMutexAndOriginalExtraChargeAreCheckedTogether() {
        var catalog = catalog();
        for (var picks :
                List.of(
                        List.of(
                                new Pick("OPTION", "O1", "0.5", 16),
                                new Pick("BUNDLE_MEMBER", "BC", "2", 63)),
                        List.of(
                                new Pick("OPTION", "O1", "0.5", 16),
                                        new Pick("OPTION", "O2", "0.5", 25),
                                new Pick("OPTION", "O3", "1", 0),
                                        new Pick("BUNDLE_MEMBER", "BC", "2", 63)),
                        List.of(new Pick("OPTION", "O3", "1", 0)),
                        List.of(new Pick("BUNDLE_MEMBER", "BC", "3", 94)),
                        List.of(new Pick("BUNDLE_MEMBER", "BC", "2", 62)))) {
            assertThatThrownBy(
                            () ->
                                    ChannelOrderSelectionPolicy.validate(
                                            command(catalog, picks), catalog))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void anotherHistoricalCatalogHashOrUnknownChoiceCannotReinterpretTheOriginalOrder() {
        var catalog = catalog();
        var original = command(catalog, valid());
        var oldSource = catalog.sources().getFirst();
        var changedSource =
                new ChannelCatalogSourcePort.ValidatedSource(
                        oldSource.owner(),
                        oldSource.sourceRef(),
                        oldSource.selectionRevision(),
                        oldSource.priority(),
                        new Validation(
                                Status.VALID,
                                oldSource.sourceRef(),
                                oldSource.selectionRevision(),
                                "b".repeat(64),
                                oldSource.proof().validUntil(),
                                "OTHER_ORIGINAL_SOURCE"));
        var changed =
                new ChannelCatalogSourcePort.Document(
                        catalog.owner(),
                        catalog.currency(),
                        List.of(changedSource),
                        catalog.sections(),
                        catalog.offers(),
                        catalog.schedules(),
                        catalog.validUntil(),
                        "OTHER_ORIGINAL_CATALOG",
                        catalog.schemaVersion(),
                        catalog.details(),
                        catalog.availability(),
                        catalog.taxBases());
        assertThat(CatalogDocumentPolicy.hash(changed, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(catalog, json));
        assertThatThrownBy(() -> ChannelOrderSelectionPolicy.validate(original, changed))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                ChannelOrderSelectionPolicy.validate(
                                        command(
                                                catalog,
                                                List.of(
                                                        new Pick(
                                                                "BUNDLE_MEMBER",
                                                                "OTHER",
                                                                "2",
                                                                63))),
                                        catalog))
                .isInstanceOf(RuntimeException.class);
    }
}
