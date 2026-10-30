package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogAvailabilityPolicy;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Offer;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.SourceSchedule;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.UnitPrice;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Variant;
import ai.neargo.shop.spi.commerce.CatalogAvailability.SourceWindow;
import ai.neargo.shop.spi.commerce.CatalogAvailability.UtcWindow;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CatalogV3PolicyTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    static CatalogTaxBasis basis(String currency, String store) {
        var definition = new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE);
        var tax = new MerchantTaxSnapshot("MAIN", "E", store, 1, MerchantTaxSnapshot.Source.MERCHANT_CUSTOM,
                "E", 1, 500, PriceTaxMode.EXCLUSIVE, definition.snapshotHash("MERCHANT_CUSTOM:E", 1));
        return new CatalogTaxBasis("TAX", "E", store, new Market("TWD".equals(currency) ? "TW" : "AE", currency, 2),
                CatalogPriceSourceFacts.taxFact(tax));
    }

    static Document upgrade(Document old, String store) {
        var start = Instant.now().truncatedTo(ChronoUnit.DAYS);
        var end = start.plus(7, ChronoUnit.DAYS);
        var availability = new CatalogAvailability(start, end, old.sources().stream().map(s ->
                new SourceWindow(s.sourceRef(), s.selectionRevision(), List.of(new UtcWindow(start, end)))).toList());
        return new Document(old.owner(), old.currency(), old.sources(), List.of(), List.of(), List.of(),
                Instant.now().plusSeconds(10), "ROOT", 3, old.details(), availability,
                List.of(basis(old.currency(), store)));
    }

    Document document() {
        var fixture = new CatalogDetailsPolicyTest();
        var old = fixture.doc(fixture.details(100, "1.25"));
        var sources = old.sources().stream().map(s -> new ValidatedSource(s.owner(), s.sourceRef(),
                s.selectionRevision(), s.priority(), new Validation(s.proof().status(), s.sourceRef(),
                s.selectionRevision(), s.proof().contentHash(), Instant.now().plusSeconds(20), "OWNER"))).toList();
        return upgrade(new Document(old.owner(), old.currency(), sources, old.sections(), old.offers(), old.schedules(),
                old.validUntil(), old.receiptNo(), old.schemaVersion(), old.details()), "S");
    }

    Document metadata(Document d, CatalogAvailability availability, List<CatalogTaxBasis> taxes) {
        return new Document(d.owner(), d.currency(), d.sources(), d.sections(), d.offers(), d.schedules(),
                d.validUntil(), d.receiptNo(), 3, d.details(), availability, taxes);
    }

    void freeze(Document d) { CatalogDocumentPolicy.freeze(d, d.sources(), json, "E", "S"); }

    @Test void acceptsCompleteV3AndKeepsEvidenceDeadlineDistinctFromSevenDayHorizon() {
        var d = document(); freeze(d);
        assertThat(CatalogDocumentPolicy.itemCount(d)).isEqualTo(1);
        assertThat(d.availability().horizonEnd()).isAfter(d.validUntil());
        assertThat(CatalogDocumentPolicy.version(d)).isEqualTo(3);
    }

    @Test void explicitEmptyCatalogNeedsNoInventedTaxDefinition() {
        var d = document();
        var empty = new ChannelCatalogDetails(List.of(), List.of(), d.details().schedules());
        var clear = new Document(d.owner(), d.currency(), d.sources(), List.of(), List.of(), List.of(),
                d.validUntil(), d.receiptNo(), 3, empty, d.availability(), List.of());
        freeze(clear);
        assertThat(CatalogDocumentPolicy.itemCount(clear)).isZero();
    }

    @Test void boundedMidnightCrossingDoesNotAcceptArbitrarilyOldCaptureHorizons() {
        var d = document();
        var now = Instant.parse("2030-01-02T00:00:10Z");
        var start = Instant.parse("2030-01-01T00:00:00Z");
        var source = d.sources().getFirst();
        var proofs = List.of(new ValidatedSource(source.owner(), "M", "R1", 0,
                new Validation(source.proof().status(), "M", "R1", source.proof().contentHash(),
                        now.plusSeconds(20), "OWNER")));
        var availability = new CatalogAvailability(start, start.plus(7, ChronoUnit.DAYS),
                List.of(new SourceWindow("M", "R1", List.of())));
        var crossing = new Document(d.owner(), d.currency(), proofs, List.of(), List.of(), List.of(),
                now.plusSeconds(10), "ROOT", 3, d.details(), availability, d.taxBases());
        CatalogAvailabilityPolicy.validate(crossing, now);
        assertThatThrownBy(() -> CatalogAvailabilityPolicy.validate(crossing, now.plusSeconds(21)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void emptyCatalogStillNeedsValidCurrencyAndTrustedPreviewScope() {
        var d = document(); var empty = new ChannelCatalogDetails(List.of(), List.of(), d.details().schedules());
        var unknown = new Document(d.owner(), "ZZZ", d.sources(), List.of(), List.of(), List.of(),
                d.validUntil(), "ROOT", 3, empty, d.availability(), List.of());
        assertThatThrownBy(() -> freeze(unknown)).isInstanceOf(RuntimeException.class);
        var clear = new Document(d.owner(), d.currency(), d.sources(), List.of(), List.of(), List.of(),
                d.validUntil(), "ROOT", 3, empty, d.availability(), List.of());
        assertThatThrownBy(() -> CatalogDocumentPolicy.freeze(clear, clear.sources(), json, null, "S"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void missingWrongScopedOrUnexplainedTaxCannotBecomeZeroTax() {
        var d = document();
        assertThatThrownBy(() -> freeze(metadata(d, d.availability(), null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> freeze(metadata(d, d.availability(), List.of(basis("AED", "OTHER")))))
                .isInstanceOf(RuntimeException.class);
        var b = d.taxBases().getFirst();
        var unused = new CatalogTaxBasis("UNUSED", b.entityNo(), b.storeNo(), b.market(), b.tax());
        assertThatThrownBy(() -> freeze(metadata(d, d.availability(), List.of(b, unused))))
                .isInstanceOf(RuntimeException.class);
        var wrong = new CatalogTaxBasis("WRONG", b.entityNo(), b.storeNo(), b.market(), b.tax());
        assertThatThrownBy(() -> freeze(metadata(d, d.availability(), List.of(wrong))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> CatalogDocumentPolicy.freeze(d, d.sources(), json, "OTHER", "S"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsMissingSourceOverlappingAdjacentUnsortedAndOutOfHorizonWindows() {
        var d = document(); var a = d.availability(); var start = a.horizonStart(); var end = a.horizonEnd();
        assertThatThrownBy(() -> freeze(metadata(d, new CatalogAvailability(start, end, List.of()), d.taxBases())))
                .isInstanceOf(RuntimeException.class);
        for (var windows : List.of(
                List.of(new UtcWindow(start, end), new UtcWindow(start, end)),
                List.of(new UtcWindow(start, start.plusSeconds(60)), new UtcWindow(start.plusSeconds(60), end)),
                List.of(new UtcWindow(start.plusSeconds(120), end), new UtcWindow(start, start.plusSeconds(60))),
                List.of(new UtcWindow(start.minusNanos(1), end)))) {
            var source = new SourceWindow("M", "R1", windows);
            assertThatThrownBy(() -> freeze(metadata(d, new CatalogAvailability(start, end, List.of(source)),
                    d.taxBases()))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void emptySourceWindowsExplicitlyMeanClosedAndNeverMissing() {
        var d = document(); var a = d.availability();
        var closed = metadata(d, new CatalogAvailability(a.horizonStart(), a.horizonEnd(),
                List.of(new SourceWindow("M", "R1", List.of()))), d.taxBases());
        freeze(closed);
        assertThat(CatalogDocumentPolicy.hash(closed, json)).isNotEqualTo(CatalogDocumentPolicy.hash(d, json));
    }

    @Test void rejectsStaleShiftedOrOversizedHorizonsAndLongLivedEvidence() {
        var d = document(); var a = d.availability();
        for (var bad : List.of(new CatalogAvailability(a.horizonStart().minus(1, ChronoUnit.DAYS),
                a.horizonEnd().minus(1, ChronoUnit.DAYS), a.sources()),
                new CatalogAvailability(a.horizonStart().plusSeconds(1), a.horizonEnd().plusSeconds(1), a.sources()),
                new CatalogAvailability(a.horizonStart(), a.horizonEnd().plusSeconds(1), a.sources()))) {
            assertThatThrownBy(() -> freeze(metadata(d, bad, d.taxBases()))).isInstanceOf(RuntimeException.class);
        }
        var extended = new Document(d.owner(), d.currency(), d.sources(), List.of(), List.of(), List.of(),
                Instant.now().plusSeconds(31), d.receiptNo(), 3, d.details(), a, d.taxBases());
        assertThatThrownBy(() -> freeze(extended)).isInstanceOf(RuntimeException.class);
    }

    @Test void stableHashIgnoresEvidenceRefreshAndCollectionOrderButTracksWindowsAndTax() {
        var d = document(); var sources = new ArrayList<>(d.sources()); Collections.reverse(sources);
        var refreshed = new Document(d.owner(), d.currency(), sources, List.of(), List.of(), List.of(),
                Instant.now().plusSeconds(9), "REFRESHED", 3, d.details(), d.availability(), d.taxBases());
        assertThat(CatalogDocumentPolicy.hash(refreshed, json)).isEqualTo(CatalogDocumentPolicy.hash(d, json));
        var a = d.availability(); var changed = metadata(d, new CatalogAvailability(a.horizonStart(), a.horizonEnd(),
                List.of(new SourceWindow("M", "R1", List.of(new UtcWindow(a.horizonStart().plusSeconds(1),
                        a.horizonEnd()))))), d.taxBases());
        assertThat(CatalogDocumentPolicy.hash(changed, json)).isNotEqualTo(CatalogDocumentPolicy.hash(d, json));
        var b = d.taxBases().getFirst(); var def = new TaxSettingDefinition(true, 600, PriceTaxMode.INCLUSIVE);
        var tax = new MerchantTaxSnapshot("MAIN", "E", "S", 2, MerchantTaxSnapshot.Source.MERCHANT_CUSTOM, "E", 2,
                600, PriceTaxMode.INCLUSIVE, def.snapshotHash("MERCHANT_CUSTOM:E", 2));
        var taxed = metadata(d, a, List.of(new CatalogTaxBasis("TAX", "E", "S", b.market(),
                CatalogPriceSourceFacts.taxFact(tax))));
        freeze(taxed);
        assertThat(CatalogDocumentPolicy.hash(taxed, json)).isNotEqualTo(CatalogDocumentPolicy.hash(d, json));
    }

    @Test void twoSourceCollectionsCanBeReorderedWithoutLosingPerSourceWindowsOrPrices() {
        var d = document(); var a = d.availability(); var source = d.sources().getFirst();
        var second = new ValidatedSource(source.owner(), "M2", "R2", 1, new Validation(source.proof().status(),
                "M2", "R2", "b".repeat(64), source.proof().validUntil(), "OTHER_RECEIPT"));
        var firstOffer = d.details().offers().getFirst(); var v = firstOffer.variants().getFirst();
        var p = v.price();
        var secondVariant = new Variant(v.skuRef(), v.name(), new UnitPrice(200L, p.currency(), p.priceSourceRef(),
                p.priceRevision(), "TAX2", "REFRESHABLE"), v.inventory(), v.optionGroups(), List.of());
        var secondOffer = new Offer("ITEM2", "M2", "R2", firstOffer.goodsRef(), "Other", firstOffer.sectionKey(),
                List.of(secondVariant), List.of());
        var schedule = d.details().schedules().getFirst();
        var otherSchedule = new SourceSchedule("M2", "R2", schedule.timezone(), schedule.validFrom(),
                schedule.validTo(), schedule.weekWindows(), schedule.exceptions());
        var b = d.taxBases().getFirst();
        var otherTax = new CatalogTaxBasis("TAX2", b.entityNo(), b.storeNo(), b.market(), b.tax());
        var otherWindow = new SourceWindow("M2", "R2", List.of(new UtcWindow(a.horizonStart().plusSeconds(3600),
                a.horizonEnd().minusSeconds(3600))));
        var details = new ChannelCatalogDetails(d.details().sections(), List.of(firstOffer, secondOffer),
                List.of(schedule, otherSchedule));
        var first = new Document(d.owner(), d.currency(), List.of(source, second), List.of(), List.of(), List.of(),
                d.validUntil(), "ROOT", 3, details,
                new CatalogAvailability(a.horizonStart(), a.horizonEnd(), List.of(a.sources().getFirst(), otherWindow)),
                List.of(b, otherTax));
        var reordered = new Document(d.owner(), d.currency(), List.of(second, source), List.of(), List.of(), List.of(),
                d.validUntil(), "REFRESHED", 3, new ChannelCatalogDetails(d.details().sections(),
                List.of(secondOffer, firstOffer), List.of(otherSchedule, schedule)),
                new CatalogAvailability(a.horizonStart(), a.horizonEnd(), List.of(otherWindow, a.sources().getFirst())),
                List.of(otherTax, b));
        freeze(first); freeze(reordered);
        assertThat(CatalogDocumentPolicy.hash(first, json)).isEqualTo(CatalogDocumentPolicy.hash(reordered, json));
        assertThat(reordered.availability().sources()).hasSize(2);
        assertThat(reordered.details().offers().getFirst().variants().getFirst().price().amountMinor()).isEqualTo(200L);
    }

    @Test void oldVersionsCannotSmuggleV3MetadataAndOldGatewaysDoNotOptIntoThree() {
        var d = document();
        var legacy = new Document(d.owner(), d.currency(), d.sources(), List.of(), List.of(), List.of(),
                d.validUntil(), "ROOT", 2, d.details(), d.availability(), d.taxBases());
        assertThatThrownBy(() -> freeze(legacy)).isInstanceOf(RuntimeException.class);
        assertThat(mock(ChannelCatalogGateway.class, CALLS_REAL_METHODS).supportsDocumentSchema(3)).isFalse();
    }

    @Test void newMetadataRejectsUnknownFieldsAndNumericInstants() {
        var text = json.writeValueAsString(document().availability());
        assertThatThrownBy(() -> json.readValue(text.replace("\"horizonStart\":\"", "\"extra\":1,\"horizonStart\":\""),
                CatalogAvailability.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"horizonStart\":123}", CatalogAvailability.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"taxBasisRef\":123}", CatalogTaxBasis.class))
                .isInstanceOf(RuntimeException.class);
    }
}
