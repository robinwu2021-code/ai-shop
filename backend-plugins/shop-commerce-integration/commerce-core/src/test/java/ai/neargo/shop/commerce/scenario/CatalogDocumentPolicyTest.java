package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.BundleChoice;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.BundleGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Offer;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Price;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Schedule;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Sku;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

class CatalogDocumentPolicyTest {
    final ObjectMapper json = new ObjectMapper();
    final Validation proof =
            new Validation(
                    Status.VALID,
                    "MENU",
                    "R1",
                    "a".repeat(64),
                    Instant.now().plusSeconds(600),
                    "SOURCE_RECEIPT");
    final List<ValidatedSource> sources =
            List.of(new ValidatedSource(Owner.FOOD, "MENU", "R1", 0, proof));

    Document document() {
        return new CatalogSynchronizationTest.Source()
                .capture(new CaptureCommand("E", "S", Owner.FOOD, "CON", "CR", sources));
    }

    Document offers(Document d, List<Offer> offers, List<Schedule> schedule) {
        return new Document(
                d.owner(),
                d.currency(),
                d.sources(),
                d.sections(),
                offers,
                schedule,
                d.validUntil(),
                d.receiptNo());
    }

    @Test
    void originalTaxCurrencyAndInventoryBasisAreMandatory() {
        var d = document();
        var o = d.offers().getFirst();
        var price = new Price(100, "AED", "PRICE", "R1", "TAX", "RECEIPT");
        var wrong =
                new Offer(
                        o.offerRef(),
                        o.sourceRef(),
                        o.selectionRevision(),
                        o.name(),
                        o.sectionKey(),
                        List.of(new Sku("SKU", "Regular", price, "STOCK")),
                        o.optionGroups(),
                        o.bundleGroups(),
                        o.images());
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freeze(
                                        offers(d, List.of(wrong), List.of()), sources, json))
                .isInstanceOf(RuntimeException.class);
        var noStock =
                new Offer(
                        o.offerRef(),
                        o.sourceRef(),
                        o.selectionRevision(),
                        o.name(),
                        o.sectionKey(),
                        List.of(new Sku("SKU", "Regular", o.skus().getFirst().price(), null)),
                        o.optionGroups(),
                        o.bundleGroups(),
                        o.images());
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freeze(
                                        offers(d, List.of(noStock), List.of()), sources, json))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void duplicateOffersAndCyclicBundlesAreRejected() {
        var d = document();
        var o = d.offers().getFirst();
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freeze(
                                        offers(d, List.of(o, o), List.of()), sources, json))
                .isInstanceOf(RuntimeException.class);
        var bundle =
                new BundleGroup(
                        "SET",
                        "Set",
                        1,
                        1,
                        List.of(
                                new BundleChoice(
                                        "CHOOSE", o.offerRef(), o.skus().getFirst().price(), 1)));
        var cycle =
                new Offer(
                        o.offerRef(),
                        o.sourceRef(),
                        o.selectionRevision(),
                        o.name(),
                        o.sectionKey(),
                        o.skus(),
                        o.optionGroups(),
                        List.of(bundle),
                        o.images());
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freeze(
                                        offers(d, List.of(cycle), List.of()), sources, json))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void schedulesAllowOvernightButRejectEmptyIntervals() {
        var d = document();
        var night =
                new Schedule(
                        "MENU",
                        "R1",
                        "Asia/Taipei",
                        List.of(1, 2),
                        LocalTime.of(22, 0),
                        LocalTime.of(2, 0),
                        null,
                        null);
        assertThat(
                        CatalogDocumentPolicy.freeze(
                                        offers(d, d.offers(), List.of(night)), sources, json)
                                .schedules())
                .containsExactly(night);
        var empty =
                new Schedule(
                        "MENU",
                        "R1",
                        "Asia/Taipei",
                        List.of(1),
                        LocalTime.NOON,
                        LocalTime.NOON,
                        null,
                        null);
        assertThatThrownBy(
                        () ->
                                CatalogDocumentPolicy.freeze(
                                        offers(d, d.offers(), List.of(empty)), sources, json))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void evidenceTtlRefreshDoesNotChangeStableBusinessHash() {
        var d = document();
        var refreshed =
                new ValidatedSource(
                        Owner.FOOD,
                        "MENU",
                        "R1",
                        0,
                        new Validation(
                                Status.VALID,
                                "MENU",
                                "R1",
                                proof.contentHash(),
                                Instant.now().plusSeconds(1200),
                                "NEW_VALIDATION_RECEIPT"));
        var other =
                new Document(
                        d.owner(),
                        d.currency(),
                        List.of(refreshed),
                        d.sections(),
                        d.offers(),
                        d.schedules(),
                        Instant.now().plusSeconds(800),
                        "NEW_CAPTURE_RECEIPT");
        assertThat(CatalogDocumentPolicy.hash(d, json))
                .isEqualTo(CatalogDocumentPolicy.hash(other, json));
        var changed =
                new Document(
                        d.owner(),
                        d.currency(),
                        d.sources(),
                        d.sections(),
                        List.of(),
                        d.schedules(),
                        d.validUntil(),
                        d.receiptNo());
        assertThat(CatalogDocumentPolicy.hash(changed, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(d, json));
    }
}
