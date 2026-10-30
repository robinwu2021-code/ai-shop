package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourcePriceMath;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.QuantityRange;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.UnitPrice;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Command;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort.Resolution;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates explicit historical selections; it supplies neither current stock nor native
 * price/payment authority.
 */
public final class ChannelOrderSelectionPolicy {
    private ChannelOrderSelectionPolicy() {}

    public record Selection(
            String externalLineRef,
            String kind,
            String groupRef,
            String choiceRef,
            String originalSourceKind,
            String originalSourceRef,
            String skuRef,
            BigDecimal picksPerUnit,
            BigDecimal physicalQuantity,
            UnitPrice publishedPrice,
            long originalExtraMinor) {}

    public record Line(
            String externalLineRef,
            String menuRef,
            String revisionRef,
            String originalOfferRef,
            String goodsRef,
            String skuRef,
            BigDecimal physicalQuantity,
            String unit,
            UnitPrice publishedMainPrice,
            List<Selection> selections) {
        public Line {
            selections = List.copyOf(selections);
        }
    }

    /**
     * Every submitted pick must belong to the exact original sale/variant/parent; defaults are not
     * silently inserted.
     */
    public static List<Line> validate(Command command, Document catalog) {
        require(
                command != null
                        && catalog != null
                        && Integer.valueOf(3).equals(catalog.schemaVersion())
                        && catalog.details() != null
                        && command.original() != null
                        && command.original()
                                .documentHash()
                                .equals(PricingSnapshotHash.of(command.original().document()))
                        && command.original()
                                .document()
                                .catalogHash()
                                .equals(CatalogDocumentPolicy.hash(catalog, new ObjectMapper())));
        var originals = command.original().document().lines();
        require(originals.size() == command.lines().size());
        var seenLines = new HashSet<String>();
        var result = new ArrayList<Line>();
        for (var mapped : command.lines()) {
            require(seenLines.add(mapped.externalLineRef()));
            var original =
                    originals.stream()
                            .filter(line -> line.lineRef().equals(mapped.externalLineRef()))
                            .findFirst()
                            .orElseThrow(ChannelOrderSelectionPolicy::invalid);
            require(
                    original.offerIdentity().equals(mapped.offer().identity())
                            && original.skuIdentity().equals(mapped.sku().identity())
                            && original.quantity().equals(mapped.quantity())
                            && original.unit().equals(mapped.unit())
                            && original.selections().size() == mapped.selections().size());
            var offer = mapped.offer();
            var sku = mapped.sku();
            resource(catalog, offer, ObjectType.OFFER);
            resource(catalog, sku, ObjectType.SKU);
            require(
                    Objects.equals(offer.salesOfferRef(), sku.salesOfferRef())
                            && offer.sourceContext()
                                    .menuRef()
                                    .equals(sku.sourceContext().menuRef()));
            var offers =
                    catalog.details().offers().stream()
                            .filter(
                                    item ->
                                            item.sourceRef().equals(offer.sourceContext().menuRef())
                                                    && item.originalOfferRef()
                                                            .equals(offer.sourceRef())
                                                    && item.selectionRevision()
                                                            .equals(offer.sourceRevision()))
                            .toList();
            require(offers.size() == 1);
            var item = offers.getFirst();
            var variants =
                    item.variants().stream()
                            .filter(variant -> variant.skuRef().equals(sku.sourceRef()))
                            .toList();
            require(variants.size() == 1);
            var variant = variants.getFirst();
            var quantity = decimal(mapped.quantity(), false);
            var measurement = item.measurement();
            ChannelOrderPolicy.quantity(mapped.quantity());
            require(
                    measurement != null
                            && original.unit().equals(measurement.unitCode())
                            && measurement.precision() != null
                            && quantity.stripTrailingZeros().scale() <= measurement.precision()
                            && ("COUNT".equals(measurement.mode()) && measurement.precision() == 0
                                    || "WEIGHT".equals(measurement.mode())
                                            && measurement.precision() >= 1
                                            && measurement.precision() <= 6));
            var selected = new ArrayList<Selection>();
            var seen = new HashSet<String>();
            var optionPicks = new HashMap<String, HashMap<String, BigDecimal>>();
            var bundlePicks = new HashMap<String, HashMap<String, BigDecimal>>();
            for (var selection : mapped.selections()) {
                require(seen.add(selection.externalLineRef()));
                var source =
                        original.selections().stream()
                                .filter(
                                        value ->
                                                value.lineRef().equals(selection.externalLineRef()))
                                .findFirst()
                                .orElseThrow(ChannelOrderSelectionPolicy::invalid);
                require(
                        source.kind().equals(selection.kind())
                                && source.quantity().equals(selection.quantity())
                                && source.amountMinor() == selection.amountMinor()
                                && source.externalIdentity()
                                        .equals(selection.mapping().identity()));
                var mapping = selection.mapping();
                resource(catalog, mapping, ObjectType.valueOf(selection.kind()));
                require(
                        item.originalOfferRef().equals(mapping.salesOfferRef())
                                && item.sourceRef().equals(mapping.sourceContext().menuRef())
                                && item.selectionRevision().equals(mapping.sourceRevision()));
                ChannelOrderPolicy.quantity(selection.quantity());
                var picks = decimal(selection.quantity(), false);
                if ("OPTION".equals(selection.kind())) {
                    var groups =
                            variant.optionGroups().stream()
                                    .filter(
                                            group ->
                                                    group.groupRef()
                                                            .equals(
                                                                    mapping.sourceContext()
                                                                            .optionGroupRef()))
                                    .toList();
                    require(groups.size() == 1);
                    var group = groups.getFirst();
                    var choices =
                            group.choices().stream()
                                    .filter(
                                            choice ->
                                                    choice.choiceRef().equals(mapping.sourceRef()))
                                    .toList();
                    require(choices.size() == 1);
                    var choice = choices.getFirst();
                    require(
                            optionPicks
                                            .computeIfAbsent(
                                                    group.groupRef(), ignored -> new HashMap<>())
                                            .putIfAbsent(choice.choiceRef(), picks)
                                    == null);
                    range(choice.quantity(), picks);
                    var physical = SourcePriceMath.quantity(quantity.multiply(picks));
                    extra(choice.price(), physical, selection.amountMinor(), catalog.currency());
                    selected.add(
                            new Selection(
                                    selection.externalLineRef(),
                                    "OPTION",
                                    group.groupRef(),
                                    choice.choiceRef(),
                                    choice.sourceKind(),
                                    choice.sourceRef(),
                                    choice.skuRef(),
                                    picks,
                                    physical,
                                    choice.price(),
                                    selection.amountMinor()));
                } else if ("BUNDLE_MEMBER".equals(selection.kind())) {
                    require(variant.skuRef().equals(mapping.sourceContext().variantSkuRef()));
                    var groups =
                            variant.bundleGroups().stream()
                                    .filter(
                                            group ->
                                                    group.groupRef()
                                                            .equals(
                                                                    mapping.sourceContext()
                                                                            .bundleGroupRef()))
                                    .toList();
                    require(groups.size() == 1);
                    var group = groups.getFirst();
                    var choices =
                            group.choices().stream()
                                    .filter(
                                            choice ->
                                                    choice.choiceRef().equals(mapping.sourceRef()))
                                    .toList();
                    require(choices.size() == 1);
                    var choice = choices.getFirst();
                    require(
                            bundlePicks
                                            .computeIfAbsent(
                                                    group.groupRef(), ignored -> new HashMap<>())
                                            .putIfAbsent(choice.choiceRef(), picks)
                                    == null);
                    range(choice.quantity(), picks);
                    var physical =
                            SourcePriceMath.quantity(
                                    quantity.multiply(picks)
                                            .multiply(decimal(choice.unitsPerPick(), false)));
                    extra(
                            choice.extraPrice(),
                            physical,
                            selection.amountMinor(),
                            catalog.currency());
                    selected.add(
                            new Selection(
                                    selection.externalLineRef(),
                                    "BUNDLE_MEMBER",
                                    group.groupRef(),
                                    choice.choiceRef(),
                                    choice.priceMode(),
                                    choice.choiceRef(),
                                    choice.skuRef(),
                                    picks,
                                    physical,
                                    choice.extraPrice(),
                                    selection.amountMinor()));
                } else throw invalid();
            }
            for (var group : variant.optionGroups()) {
                var picks = optionPicks.getOrDefault(group.groupRef(), new HashMap<>());
                range(group.quantity(), sum(picks.values()));
                for (var choice : group.choices())
                    range(
                            choice.quantity(),
                            picks.getOrDefault(choice.choiceRef(), BigDecimal.ZERO));
                rules(group, picks.keySet());
            }
            for (var group : variant.bundleGroups()) {
                var picks = bundlePicks.getOrDefault(group.groupRef(), new HashMap<>());
                range(group.quantity(), sum(picks.values()));
                for (var choice : group.choices())
                    range(
                            choice.quantity(),
                            picks.getOrDefault(choice.choiceRef(), BigDecimal.ZERO));
            }
            result.add(
                    new Line(
                            mapped.externalLineRef(),
                            item.sourceRef(),
                            item.selectionRevision(),
                            item.originalOfferRef(),
                            item.goodsRef(),
                            variant.skuRef(),
                            quantity,
                            original.unit(),
                            variant.price(),
                            selected));
        }
        return List.copyOf(result);
    }

    private static void resource(Document catalog, Resolution resolution, ObjectType type) {
        require(
                resolution != null
                        && resolution.objectType() == type
                        && resolution.sourceOwner() == catalog.owner()
                        && resolution.sourceContext() != null
                        && resolution.identity() != null);
        var sources =
                catalog.sources().stream()
                        .filter(
                                source ->
                                        source.sourceRef()
                                                        .equals(
                                                                resolution
                                                                        .sourceContext()
                                                                        .menuRef())
                                                && source.selectionRevision()
                                                        .equals(resolution.sourceRevision()))
                        .toList();
        require(
                sources.size() == 1
                        && sources.getFirst()
                                .proof()
                                .contentHash()
                                .equals(resolution.sourceHash()));
        CommerceValidation.text(resolution.historicalExternalEvidenceRef(), 256);
        CommerceValidation.text(resolution.freshSourceReceiptNo(), 256);
    }

    private static void rules(ChannelCatalogDetails.OptionGroup group, Set<String> selected) {
        if (group.rules() == null) return;
        for (var mutex : group.rules().mutexSets())
            require(mutex.stream().filter(selected::contains).count() <= 1);
        for (var rule : group.rules().requires())
            if (selected.contains(rule.choiceRef()))
                require(selected.containsAll(rule.requiresRefs()));
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void range(QuantityRange range, BigDecimal quantity) {
        require(
                range != null
                        && quantity.compareTo(decimal(range.minimum(), true)) >= 0
                        && quantity.compareTo(decimal(range.maximum(), true)) <= 0);
    }

    private static BigDecimal decimal(String value, boolean zero) {
        require(value != null && value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?"));
        var quantity = new BigDecimal(value);
        require(quantity.signum() > 0 || zero && quantity.signum() == 0);
        require(quantity.compareTo(new BigDecimal("99999999999999.999999")) <= 0);
        return quantity;
    }

    private static void extra(UnitPrice price, BigDecimal quantity, long extra, String currency) {
        require(
                price != null
                        && price.amountMinor() != null
                        && currency.equals(price.currency())
                        && SourcePriceMath.extend(
                                                new MoneyMinor(currency, price.amountMinor()),
                                                quantity)
                                        .minor()
                                == extra);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Invalid original channel selection");
    }

    private static void require(boolean value) {
        if (!value) throw invalid();
    }
}
