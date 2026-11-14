package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.BundleGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.BundleMember;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.DateException;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Dependency;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Inventory;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Offer;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Option;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.OptionGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.QuantityRange;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.SelectionRules;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.SourceSchedule;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.UnitPrice;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Variant;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.WeekWindow;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Section;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

class CatalogDetailsPolicyTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final List<ValidatedSource> sources =
            List.of(
                    new ValidatedSource(
                            Owner.FOOD,
                            "M",
                            "R1",
                            0,
                            new Validation(
                                    Status.VALID,
                                    "M",
                                    "R1",
                                    "a".repeat(64),
                                    Instant.now().plusSeconds(600),
                                    "OWNER")));
    static final Inventory UNKNOWN = new Inventory("UNKNOWN", null, null);

    static QuantityRange range(String min, String max, String defaults) {
        return new QuantityRange(min, max, defaults);
    }

    static UnitPrice price(long amount) {
        return new UnitPrice(amount, "AED", "SOURCE", "REV", "TAX", "RECEIPT");
    }

    ChannelCatalogDetails details(long amount, String units) {
        var child =
                new BundleMember(
                        "CH",
                        "Chicken",
                        "S2",
                        "ADD_SKU",
                        price(25),
                        UNKNOWN,
                        units,
                        range("0", "2", "0"));
        var variant =
                new Variant(
                        "S1",
                        "Regular",
                        price(amount),
                        UNKNOWN,
                        List.of(),
                        List.of(
                                new BundleGroup(
                                        "BG", "Choose", range("0", "2", null), List.of(child))));
        return new ChannelCatalogDetails(
                List.of(new Section("CAT", "Menu", 0)),
                List.of(
                        new Offer(
                                "ITEM", "M", "R1", "G", "Set", "CAT", List.of(variant), List.of())),
                List.of(schedule(List.of(), List.of())));
    }

    Document doc(ChannelCatalogDetails detail) {
        return new Document(
                Owner.FOOD,
                "AED",
                sources,
                List.of(),
                List.of(),
                List.of(),
                Instant.now().plusSeconds(30),
                "ROOT",
                2,
                detail);
    }

    @Test
    void priceAndExactUnitsArePartOfTheVersionTwoBusinessHash() {
        var original = doc(details(100, "0.333"));
        assertThat(CatalogDocumentPolicy.hash(original, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(doc(details(101, "0.333")), json));
        assertThat(CatalogDocumentPolicy.hash(original, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(doc(details(100, "0.334")), json));
    }

    @Test
    void malformedDecimalQuantitiesAreNotSilentlyAcceptedAsEmptyLegacyCatalogs() {
        assertThatThrownBy(
                        () -> CatalogDocumentPolicy.freeze(doc(details(100, "0")), sources, json))
                .isInstanceOf(RuntimeException.class);
    }

    static SourceSchedule schedule(List<WeekWindow> weeks, List<DateException> exceptions) {
        return new SourceSchedule(
                "M",
                "R1",
                "Asia/Dubai",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                weeks,
                exceptions);
    }

    static WeekWindow window(int day, String from, String to, int offset) {
        return new WeekWindow(day, LocalTime.parse(from), LocalTime.parse(to), offset);
    }

    static Option option(String key, String min, String max, String defaults) {
        return new Option(
                key, key, "MODIFIER", key, "MODSKU", price(25), UNKNOWN, range(min, max, defaults));
    }

    ChannelCatalogDetails options(List<Option> choices, SelectionRules rules) {
        var group = new OptionGroup("OG", "Toppings", range("0", "3", null), choices, rules);
        var a = new Variant("S1", "Small", price(100), UNKNOWN, List.of(group), List.of());
        var b = new Variant("S2", "Large", price(200), UNKNOWN, List.of(), List.of());
        return new ChannelCatalogDetails(
                details(100, "1").sections(),
                List.of(new Offer("ITEM", "M", "R1", "G", "Tea", "CAT", List.of(a, b), List.of())),
                List.of(schedule(List.of(), List.of())));
    }

    ChannelCatalogDetails schedules(List<SourceSchedule> schedules) {
        var d = details(100, "0.333");
        return new ChannelCatalogDetails(d.sections(), d.offers(), schedules);
    }

    void rejected(ChannelCatalogDetails details) {
        assertThatThrownBy(() -> CatalogDocumentPolicy.freeze(doc(details), sources, json))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void exactPerSkuRulesArePreservedAndNotAppliedToAnotherVariant() {
        var detail =
                options(
                        List.of(option("MILK", "0", "1.5", "0"), option("PEARL", "0", "2", "0")),
                        new SelectionRules(
                                List.of(), List.of(new Dependency("PEARL", List.of("MILK")))));
        var frozen = CatalogDocumentPolicy.freeze(doc(detail), sources, json);
        assertThat(
                        frozen.details()
                                .offers()
                                .getFirst()
                                .variants()
                                .getFirst()
                                .optionGroups()
                                .getFirst()
                                .choices()
                                .getFirst()
                                .quantity()
                                .maximum())
                .isEqualTo("1.5");
        assertThat(frozen.details().offers().getFirst().variants().getLast().optionGroups())
                .isEmpty();
        assertThat(
                        frozen.details()
                                .offers()
                                .getFirst()
                                .variants()
                                .getFirst()
                                .optionGroups()
                                .getFirst()
                                .rules()
                                .requires()
                                .getFirst()
                                .requiresRefs())
                .containsExactly("MILK");
    }

    @Test
    void unknownReferencesCyclesAndDefaultMutexConflictsAreRejected() {
        var choices = List.of(option("A", "0", "1", "0"), option("B", "0", "1", "0"));
        for (var rules :
                List.of(
                        new SelectionRules(List.of(List.of("A", "MISSING")), List.of()),
                        new SelectionRules(
                                List.of(),
                                List.of(
                                        new Dependency("A", List.of("B")),
                                        new Dependency("B", List.of("A")))),
                        new SelectionRules(
                                List.of(), List.of(new Dependency("A", List.of("MISSING"))))))
            rejected(options(choices, rules));
        rejected(
                options(
                        List.of(option("A", "0", "1", "1"), option("B", "0", "1", "1")),
                        new SelectionRules(List.of(List.of("A", "B")), List.of())));
        rejected(
                options(
                        List.of(option("A", "0", "1", "1"), option("B", "0", "1", "0")),
                        new SelectionRules(List.of(), List.of(new Dependency("A", List.of("B"))))));
    }

    @Test
    void malformedBoundsAndZeroNegativeOrCoercedPerPickAmountsAreRejected() {
        for (String bad : List.of("0", "-1", "1e2", "1.0000001", " 1", "01", "9999999999999"))
            rejected(details(100, bad));
        for (var bad :
                List.of(
                        option("A", "2", "1", "1"),
                        option("A", "0", "1", "2"),
                        option("A", "4", "4", "4")))
            rejected(options(List.of(bad), new SelectionRules(List.of(), List.of())));
    }

    @Test
    void overnightFullDayAndDateExceptionsSurviveTheFrozenDocument() {
        var original =
                schedules(
                        List.of(
                                schedule(
                                        List.of(
                                                window(1, "22:00", "02:00", 1),
                                                window(3, "00:00", "00:00", 1)),
                                        List.of(
                                                new DateException(
                                                        LocalDate.of(2026, 10, 26),
                                                        "CLOSED",
                                                        null,
                                                        null,
                                                        0),
                                                new DateException(
                                                        LocalDate.of(2026, 10, 27),
                                                        "OPEN",
                                                        LocalTime.of(8, 0),
                                                        LocalTime.of(12, 0),
                                                        0)))));
        var frozen = CatalogDocumentPolicy.freeze(doc(original), sources, json);
        assertThat(frozen.details().schedules().getFirst().weekWindows().get(1).endDayOffset())
                .isEqualTo(1);
        assertThat(frozen.details().schedules().getFirst().exceptions())
                .extracting(e -> e.mode())
                .containsExactly("CLOSED", "OPEN");
        assertThat(CatalogDocumentPolicy.hash(frozen, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(doc(details(100, "0.333")), json));
    }

    @Test
    void weeklyBoundaryOverlapAndInvalidDateExceptionsAreNotSilentlyDropped() {
        rejected(
                schedules(
                        List.of(
                                schedule(
                                        List.of(
                                                window(7, "22:00", "02:00", 1),
                                                window(1, "01:00", "03:00", 0)),
                                        List.of()))));
        rejected(schedules(List.of(schedule(List.of(window(1, "12:00", "12:00", 0)), List.of()))));
        for (var bad :
                List.of(
                        new DateException(LocalDate.of(2027, 1, 1), "CLOSED", null, null, 0),
                        new DateException(
                                LocalDate.of(2026, 10, 26), "CLOSED", LocalTime.NOON, null, 0),
                        new DateException(LocalDate.of(2026, 10, 26), "OPEN", null, null, 0)))
            rejected(schedules(List.of(schedule(List.of(), List.of(bad)))));
        var closed = new DateException(LocalDate.of(2026, 10, 26), "CLOSED", null, null, 0);
        rejected(schedules(List.of(schedule(List.of(), List.of(closed, closed)))));
        rejected(schedules(List.of()));
    }

    @Test
    void explicitUnknownStockAndFreeSpecsNeverBecomeFakeZeroStockOrPaidSkus() {
        var free =
                new Option(
                        "SUGAR",
                        "Sugar",
                        "SPEC_VALUE",
                        "SWEET",
                        null,
                        price(0),
                        new Inventory("NOT_APPLICABLE", null, null),
                        range("0", "1", "0"));
        assertThat(
                        CatalogDocumentPolicy.freeze(
                                        doc(
                                                options(
                                                        List.of(free),
                                                        new SelectionRules(List.of(), List.of()))),
                                        sources,
                                        json)
                                .details())
                .isNotNull();
        var bad =
                new Option(
                        "SUGAR",
                        "Sugar",
                        "SPEC_VALUE",
                        "SWEET",
                        "PAID",
                        price(1),
                        UNKNOWN,
                        range("0", "1", "0"));
        rejected(options(List.of(bad), new SelectionRules(List.of(), List.of())));
        for (var inventory :
                List.of(
                        new Inventory("KNOWN_ZERO", null, null),
                        new Inventory("UNKNOWN", "STOCK", null),
                        new Inventory("POLICY", null, null),
                        new Inventory("NOT_APPLICABLE", null, null))) {
            var base = details(100, "1");
            var v = base.offers().getFirst().variants().getFirst();
            var wrong =
                    new Variant(
                            v.skuRef(),
                            v.name(),
                            v.price(),
                            inventory,
                            v.optionGroups(),
                            v.bundleGroups());
            rejected(withVariant(base, wrong));
        }
    }

    ChannelCatalogDetails withVariant(ChannelCatalogDetails d, Variant v) {
        var o = d.offers().getFirst();
        return new ChannelCatalogDetails(
                d.sections(),
                List.of(
                        new Offer(
                                o.offerRef(),
                                o.sourceRef(),
                                o.selectionRevision(),
                                o.goodsRef(),
                                o.name(),
                                o.sectionKey(),
                                List.of(v),
                                o.images())),
                d.schedules());
    }

    @Test
    void currenciesTaxBasisAndJsonSafePriceBoundsRemainOriginalAuthorityRequirements() {
        var d = details(100, "1");
        var v = d.offers().getFirst().variants().getFirst();
        for (var p :
                List.of(
                        price(-1),
                        price(9007199254740992L),
                        new UnitPrice(100L, "CNY", "SOURCE", "REV", "TAX", "R"),
                        new UnitPrice(100L, "AED", "SOURCE", "REV", null, "R")))
            rejected(
                    withVariant(
                            d,
                            new Variant(
                                    v.skuRef(),
                                    v.name(),
                                    p,
                                    v.inventory(),
                                    v.optionGroups(),
                                    v.bundleGroups())));
    }

    @Test
    void unknownVersionsAndMixedLegacyAndDetailedStructuresNeverDowngrade() {
        var valid = doc(details(100, "1"));
        for (var bad :
                List.of(
                        new Document(
                                valid.owner(),
                                valid.currency(),
                                sources,
                                List.of(),
                                List.of(),
                                List.of(),
                                valid.validUntil(),
                                "R",
                                3,
                                valid.details()),
                        new Document(
                                valid.owner(),
                                valid.currency(),
                                sources,
                                List.of(new Section("OLD", "Old", 0)),
                                List.of(),
                                List.of(),
                                valid.validUntil(),
                                "R",
                                2,
                                valid.details()),
                        new Document(
                                valid.owner(),
                                valid.currency(),
                                sources,
                                List.of(),
                                List.of(),
                                List.of(),
                                valid.validUntil(),
                                "R",
                                null,
                                valid.details())))
            assertThatThrownBy(() -> CatalogDocumentPolicy.freeze(bad, sources, json))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refreshedReceiptsDoNotCreateAFalseBusinessChangeButPriceRevisionsDo() {
        var original = doc(details(100, "0.333"));
        String raw = json.writeValueAsString(original);
        var refreshed = json.readValue(raw.replace("RECEIPT", "REFRESHED"), Document.class);
        assertThat(CatalogDocumentPolicy.hash(original, json))
                .isEqualTo(CatalogDocumentPolicy.hash(refreshed, json));
        var changed = json.readValue(raw.replace("\"REV\"", "\"REV2\""), Document.class);
        assertThat(CatalogDocumentPolicy.hash(original, json))
                .isNotEqualTo(CatalogDocumentPolicy.hash(changed, json));
    }

    @Test
    void strictWireAmountsRejectNumericQuantityCoercionAndUnknownDetailsFields() {
        var raw = json.writeValueAsString(doc(details(100, "0.333")));
        for (String bad :
                List.of(
                        raw.replace("\"unitsPerPick\":\"0.333\"", "\"unitsPerPick\":0.333"),
                        raw.replace("\"amountMinor\":100", "\"amountMinor\":\"100\""),
                        raw.replace("\"weekWindows\":[]", "\"weekWindows\":[],\"invented\":1")))
            assertThatThrownBy(() -> json.readValue(bad, Document.class))
                    .isInstanceOf(RuntimeException.class);
    }

    static Document fromLegacy(Document d) {
        var source = d.sources().getFirst();
        var old = d.offers().getFirst();
        var sku = old.skus().getFirst();
        var p = sku.price();
        var price =
                new UnitPrice(
                        p.amountMinor(),
                        p.currency(),
                        p.priceSourceRef(),
                        p.priceRevision(),
                        p.taxBasisRef(),
                        p.receiptNo());
        var child =
                new BundleMember(
                        "CH",
                        "Child",
                        "CHILD",
                        "ADD_SKU",
                        price,
                        UNKNOWN,
                        "0.333",
                        range("0", "1", "0"));
        var v =
                new Variant(
                        sku.skuRef(),
                        sku.name(),
                        price,
                        UNKNOWN,
                        List.of(),
                        List.of(
                                new BundleGroup(
                                        "BG", "Choose", range("0", "1", null), List.of(child))));
        var details =
                new ChannelCatalogDetails(
                        d.sections(),
                        List.of(
                                new Offer(
                                        old.offerRef(),
                                        source.sourceRef(),
                                        source.selectionRevision(),
                                        "GOODS",
                                        old.name(),
                                        old.sectionKey(),
                                        List.of(v),
                                        old.images())),
                        List.of(
                                new SourceSchedule(
                                        source.sourceRef(),
                                        source.selectionRevision(),
                                        "UTC",
                                        null,
                                        null,
                                        List.of(),
                                        List.of())));
        return new Document(
                d.owner(),
                d.currency(),
                d.sources(),
                List.of(),
                List.of(),
                List.of(),
                d.sources().stream()
                        .map(s -> s.proof().validUntil())
                        .min(Instant::compareTo)
                        .orElseThrow(),
                d.receiptNo(),
                2,
                details);
    }

    @Test
    void historicalVersionOnePayloadAndLiteralHashRemainUnchanged() {
        var oldSources =
                List.of(
                        new ValidatedSource(
                                Owner.RETAIL,
                                "M",
                                "R1",
                                0,
                                new Validation(
                                        Status.VALID,
                                        "M",
                                        "R1",
                                        "a".repeat(64),
                                        Instant.now().plusSeconds(600),
                                        "OWNER")));
        var oldPrice = new ChannelCatalogSourcePort.Price(100, "TWD", "SOURCE", "REV", "TAX", "R");
        var old =
                new Document(
                        Owner.RETAIL,
                        "TWD",
                        oldSources,
                        List.of(new Section("CAT", "Menu", 0)),
                        List.of(
                                new ChannelCatalogSourcePort.Offer(
                                        "ITEM",
                                        "M",
                                        "R1",
                                        "Tea",
                                        "CAT",
                                        List.of(
                                                new ChannelCatalogSourcePort.Sku(
                                                        "S", "One", oldPrice, "STOCK")),
                                        List.of(),
                                        List.of(),
                                        List.of())),
                        List.of(),
                        Instant.now().plusSeconds(30),
                        "ROOT");
        String raw = json.writeValueAsString(old);
        assertThat(raw).doesNotContain("schemaVersion", "details");
        var read = json.readValue(raw, Document.class);
        assertThat(CatalogDocumentPolicy.version(read)).isEqualTo(1);
        assertThat(CatalogDocumentPolicy.hash(read, json))
                .isEqualTo("2472cf5724021c7d7a50afe74db9ff207a01dd9911b842749b80f7011757a0f7");
        assertThat(CatalogDocumentPolicy.freeze(read, oldSources, json).offers()).hasSize(1);
    }

    @Test
    void configuredDefaultQuantitiesCannotExceedTheirGroupMaximum() {
        rejected(
                options(
                        List.of(option("A", "0", "2", "2"), option("B", "0", "2", "2")),
                        new SelectionRules(List.of(), List.of())));
        var d = details(100, "1");
        var v = d.offers().getFirst().variants().getFirst();
        var one =
                new BundleMember(
                        "A", "A", "S2", "ADD_SKU", price(25), UNKNOWN, "1", range("0", "2", "2"));
        var two =
                new BundleMember(
                        "B", "B", "S3", "ADD_SKU", price(25), UNKNOWN, "1", range("0", "2", "2"));
        rejected(
                withVariant(
                        d,
                        new Variant(
                                v.skuRef(),
                                v.name(),
                                v.price(),
                                v.inventory(),
                                List.of(),
                                List.of(
                                        new BundleGroup(
                                                "BG",
                                                "Bundle",
                                                range("0", "2", null),
                                                List.of(one, two))))));
    }

    @Test
    void distinctOriginalSourcePairsCannotCollideThroughDelimiterConcatenation() {
        var until = Instant.now().plusSeconds(600);
        var refs =
                List.of(
                        new ValidatedSource(
                                Owner.FOOD,
                                "M:R",
                                "1",
                                0,
                                new Validation(
                                        Status.VALID, "M:R", "1", "a".repeat(64), until, "A")),
                        new ValidatedSource(
                                Owner.FOOD,
                                "M",
                                "R:1",
                                0,
                                new Validation(
                                        Status.VALID, "M", "R:1", "b".repeat(64), until, "B")));
        var windows =
                refs.stream()
                        .map(
                                r ->
                                        new SourceSchedule(
                                                r.sourceRef(),
                                                r.selectionRevision(),
                                                "UTC",
                                                null,
                                                null,
                                                List.of(),
                                                List.of()))
                        .toList();
        var document =
                new Document(
                        Owner.FOOD,
                        "AED",
                        refs,
                        List.of(),
                        List.of(),
                        List.of(),
                        Instant.now().plusSeconds(30),
                        "R",
                        2,
                        new ChannelCatalogDetails(List.of(), List.of(), windows));
        assertThat(CatalogDocumentPolicy.freeze(document, refs, json).details().schedules())
                .hasSize(2);
    }
}
