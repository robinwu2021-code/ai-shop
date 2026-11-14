package ai.neargo.shop.commerce.policy;

import static ai.neargo.shop.commerce.policy.CatalogDocumentPolicy.require;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Dependency;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Inventory;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.QuantityRange;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.SelectionRules;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.SourceSchedule;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.UnitPrice;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates a version-two graph without coercing quantities, losing exceptions or inventing stock
 * balances.
 */
public final class CatalogDetailsPolicy {
    private CatalogDetailsPolicy() {}

    private static void text(String value) {
        CommerceValidation.text(value, 128);
    }

    private static <T> List<T> list(List<T> value, int max) {
        require(value != null && value.size() <= max && value.stream().noneMatch(Objects::isNull));
        return value;
    }

    private static void unique(List<String> refs) {
        refs.forEach(CatalogDetailsPolicy::text);
        require(new HashSet<>(refs).size() == refs.size());
    }

    private static String sourceKey(String ref, String revision) {
        text(ref);
        text(revision);
        return ChannelIdentity.part(ref) + ChannelIdentity.part(revision);
    }

    private static final class Budget {
        int nodes;

        void add(int count) {
            nodes = Math.addExact(nodes, count);
            require(nodes <= 100000);
        }
    }

    public static void validate(Document document) {
        var details = document.details();
        require(details != null);
        var sources = new HashSet<String>();
        list(document.sources(), 100);
        for (var source : document.sources()) {
            var proof = source.proof();
            require(
                    source.owner() == document.owner()
                            && proof != null
                            && proof.status() == Status.VALID
                            && source.sourceRef().equals(proof.canonicalRef())
                            && source.selectionRevision().equals(proof.canonicalRevision())
                            && proof.contentHash() != null
                            && proof.contentHash().matches("[0-9a-f]{64}")
                            && proof.validUntil() != null
                            && !document.validUntil().isAfter(proof.validUntil()));
            text(proof.ownerReceiptNo());
            require(sources.add(sourceKey(source.sourceRef(), source.selectionRevision())));
        }
        require(
                !sources.isEmpty()
                        || Integer.valueOf(3).equals(document.schemaVersion())
                                && document.replacement() != null
                                && !document.replacement().withdrawals().isEmpty());
        list(details.sections(), 500);
        list(details.offers(), 10000);
        list(details.schedules(), 500);
        unique(details.sections().stream().map(s -> s.key()).toList());
        unique(details.offers().stream().map(o -> o.offerRef()).toList());
        var sections = new HashSet<>(details.sections().stream().map(s -> s.key()).toList());
        details.sections()
                .forEach(
                        s -> {
                            text(s.name());
                            if (s.source() != null) {
                                require(Integer.valueOf(3).equals(document.schemaVersion()));
                                text(s.source().sourceRef());
                                text(s.source().selectionRevision());
                                text(s.source().categoryRef());
                                require(
                                        sources.contains(
                                                sourceKey(
                                                        s.source().sourceRef(),
                                                        s.source().selectionRevision())));
                            }
                        });
        var budget = new Budget();
        for (var offer : details.offers()) {
            text(offer.name());
            if (offer.presentation() != null || offer.measurement() != null) {
                require(Integer.valueOf(3).equals(document.schemaVersion()));
                var display = offer.presentation();
                var measurement = offer.measurement();
                if (display != null) {
                    require(
                            display.preparationSeconds() != null
                                    && display.preparationSeconds() >= 0);
                    for (var localized : list(display.localizedNames(), 20)) {
                        text(localized.locale());
                        text(localized.name());
                    }
                    list(display.tags(), 50).forEach(CatalogDetailsPolicy::text);
                    list(display.allergenNotes(), 50).forEach(v -> CommerceValidation.text(v, 512));
                }
                if (measurement != null) {
                    text(measurement.unitCode());
                    require(
                            Set.of("COUNT", "WEIGHT").contains(measurement.mode())
                                    && measurement.precision() != null
                                    && measurement.precision() >= 0
                                    && measurement.precision() <= 6);
                    if (measurement.allowedDeviation() != null)
                        decimal(measurement.allowedDeviation());
                }
            }
            text(offer.goodsRef());
            if (offer.originalOfferRef() != null) {
                require(Integer.valueOf(3).equals(document.schemaVersion()));
                text(offer.originalOfferRef());
            }
            require(sections.contains(offer.sectionKey()));
            require(sources.contains(sourceKey(offer.sourceRef(), offer.selectionRevision())));
            require(!list(offer.variants(), 100).isEmpty());
            budget.add(offer.variants().size());
            unique(offer.variants().stream().map(v -> v.skuRef()).toList());
            for (var variant : offer.variants()) {
                text(variant.name());
                price(variant.price(), document.currency());
                inventory(variant.inventory(), false);
                list(variant.optionGroups(), 100);
                list(variant.bundleGroups(), 100);
                unique(variant.optionGroups().stream().map(g -> g.groupRef()).toList());
                unique(variant.bundleGroups().stream().map(g -> g.groupRef()).toList());
                for (var group : variant.optionGroups()) {
                    text(group.name());
                    bounds(group.quantity(), false);
                    require(!list(group.choices(), 100).isEmpty());
                    budget.add(group.choices().size());
                    unique(group.choices().stream().map(c -> c.choiceRef()).toList());
                    var maximum = BigDecimal.ZERO;
                    var minimum = BigDecimal.ZERO;
                    var defaults = BigDecimal.ZERO;
                    var selected = new HashSet<String>();
                    for (var choice : group.choices()) {
                        text(choice.name());
                        text(choice.sourceRef());
                        bounds(choice.quantity(), true);
                        price(choice.price(), document.currency());
                        boolean spec = "SPEC_VALUE".equals(choice.sourceKind());
                        require(spec || "MODIFIER".equals(choice.sourceKind()));
                        if (spec)
                            require(
                                    choice.skuRef() == null
                                            && choice.price().amountMinor() == 0
                                            && choice.inventory() != null
                                            && "NOT_APPLICABLE"
                                                    .equals(choice.inventory().status()));
                        else text(choice.skuRef());
                        inventory(choice.inventory(), spec);
                        minimum = minimum.add(decimal(choice.quantity().minimum()));
                        defaults = defaults.add(decimal(choice.quantity().defaultQuantity()));
                        maximum = maximum.add(decimal(choice.quantity().maximum()));
                        if (decimal(choice.quantity().defaultQuantity()).signum() > 0)
                            selected.add(choice.choiceRef());
                    }
                    feasible(group.quantity(), minimum, maximum);
                    require(defaults.compareTo(decimal(group.quantity().maximum())) <= 0);
                    rules(
                            group.rules(),
                            group.choices().stream().map(c -> c.choiceRef()).toList(),
                            selected);
                }
                for (var group : variant.bundleGroups()) {
                    if (group.baseSkuRef() != null) {
                        require(Integer.valueOf(3).equals(document.schemaVersion()));
                        text(group.baseSkuRef());
                    }
                    text(group.name());
                    bounds(group.quantity(), false);
                    require(!list(group.choices(), 100).isEmpty());
                    budget.add(group.choices().size());
                    unique(group.choices().stream().map(c -> c.choiceRef()).toList());
                    var maximum = BigDecimal.ZERO;
                    var minimum = BigDecimal.ZERO;
                    var defaults = BigDecimal.ZERO;
                    for (var member : group.choices()) {
                        text(member.name());
                        text(member.skuRef());
                        price(member.extraPrice(), document.currency());
                        inventory(member.inventory(), false);
                        bounds(member.quantity(), true);
                        require(
                                Set.of("INCLUDED", "ADD_SKU", "DIFF_BASE")
                                        .contains(member.priceMode()));
                        require(
                                !"INCLUDED".equals(member.priceMode())
                                        || member.extraPrice().amountMinor() == 0);
                        require(decimal(member.unitsPerPick()).signum() > 0);
                        minimum = minimum.add(decimal(member.quantity().minimum()));
                        defaults = defaults.add(decimal(member.quantity().defaultQuantity()));
                        maximum = maximum.add(decimal(member.quantity().maximum()));
                    }
                    feasible(group.quantity(), minimum, maximum);
                    require(defaults.compareTo(decimal(group.quantity().maximum())) <= 0);
                }
            }
            list(offer.images(), 20);
            var imageKeys = new HashSet<String>();
            for (var image : offer.images()) {
                CommerceValidation.text(image.assetNo(), 512);
                text(image.receiptNo());
                if (image.sourceRevision() != null) {
                    require(Integer.valueOf(3).equals(document.schemaVersion()));
                    text(image.sourceRevision());
                }
                CommerceValidation.text(image.url(), 2048);
                require(imageKeys.add(image.assetNo()));
                var uri = URI.create(image.url());
                require(
                        "https".equals(uri.getScheme())
                                && uri.getHost() != null
                                && uri.getUserInfo() == null
                                && uri.getFragment() == null);
            }
        }
        var scheduled = new HashSet<String>();
        for (var schedule : details.schedules()) {
            String key = sourceKey(schedule.sourceRef(), schedule.selectionRevision());
            require(sources.contains(key) && scheduled.add(key));
            schedule(schedule);
        }
        require(
                scheduled.equals(sources)
                        || Integer.valueOf(3).equals(document.schemaVersion())
                                && details.schedules().isEmpty());
    }

    private static void price(UnitPrice price, String currency) {
        require(price != null && price.amountMinor() != null && currency.equals(price.currency()));
        new MoneyMinor(currency, price.amountMinor()).requireNonNegative().requireJsonSafe();
        text(price.priceSourceRef());
        text(price.priceRevision());
        text(price.taxBasisRef());
        text(price.receiptNo());
    }

    private static void inventory(Inventory value, boolean spec) {
        require(value != null);
        if ("UNKNOWN".equals(value.status()) || spec && "NOT_APPLICABLE".equals(value.status()))
            require(value.policyRef() == null && value.receiptNo() == null);
        else {
            require("POLICY".equals(value.status()));
            text(value.policyRef());
            text(value.receiptNo());
        }
    }

    private static BigDecimal decimal(String value) {
        require(
                value != null
                        && value.length() <= 24
                        && value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?"));
        var n = new BigDecimal(value);
        require(n.compareTo(new BigDecimal("999999999999.999999")) <= 0);
        return n;
    }

    private static void bounds(QuantityRange value, boolean defaults) {
        require(value != null);
        var minimum = decimal(value.minimum());
        var maximum = decimal(value.maximum());
        require(minimum.compareTo(maximum) <= 0);
        if (defaults || value.defaultQuantity() != null) {
            var d = decimal(value.defaultQuantity());
            require(d.compareTo(minimum) >= 0 && d.compareTo(maximum) <= 0);
        }
    }

    private static void feasible(QuantityRange group, BigDecimal minimum, BigDecimal maximum) {
        require(
                decimal(group.minimum()).compareTo(maximum) <= 0
                        && decimal(group.maximum()).compareTo(minimum) >= 0);
    }

    private static void rules(SelectionRules rules, List<String> choices, Set<String> defaults) {
        require(rules != null);
        list(rules.mutexSets(), 100);
        list(rules.requires(), 100);
        var mutexSeen = new HashSet<Set<String>>();
        for (var mutex : rules.mutexSets()) {
            list(mutex, 100);
            require(mutex.size() >= 2);
            unique(mutex);
            require(choices.containsAll(mutex));
            require(
                    mutexSeen.add(Set.copyOf(mutex))
                            && mutex.stream().filter(defaults::contains).count() <= 1);
        }
        var edges = new HashMap<String, List<String>>();
        for (Dependency dependency : rules.requires()) {
            text(dependency.choiceRef());
            require(choices.contains(dependency.choiceRef()));
            list(dependency.requiresRefs(), 100);
            require(!dependency.requiresRefs().isEmpty());
            unique(dependency.requiresRefs());
            require(
                    choices.containsAll(dependency.requiresRefs())
                            && !dependency.requiresRefs().contains(dependency.choiceRef())
                            && edges.putIfAbsent(dependency.choiceRef(), dependency.requiresRefs())
                                    == null);
            if (defaults.contains(dependency.choiceRef()))
                require(defaults.containsAll(dependency.requiresRefs()));
        }
        var done = new HashSet<String>();
        for (String choice : choices) visit(choice, edges, new HashSet<>(), done);
    }

    private static void visit(
            String choice, Map<String, List<String>> edges, Set<String> active, Set<String> done) {
        if (done.contains(choice)) return;
        require(active.add(choice));
        for (String child : edges.getOrDefault(choice, List.of()))
            visit(child, edges, active, done);
        active.remove(choice);
        done.add(choice);
    }

    private static void date(LocalDate date) {
        if (date != null) require(date.getYear() >= 1 && date.getYear() <= 9999);
    }

    private static int minute(LocalTime time) {
        require(time != null && time.getSecond() == 0 && time.getNano() == 0);
        return time.getHour() * 60 + time.getMinute();
    }

    private static int duration(LocalTime from, LocalTime to, Integer offset) {
        require(offset != null && (offset == 0 || offset == 1));
        int n = minute(to) - minute(from) + offset * 1440;
        require(n > 0 && n <= 1440);
        return n;
    }

    private static void schedule(SourceSchedule schedule) {
        text(schedule.timezone());
        ZoneId.of(schedule.timezone());
        date(schedule.validFrom());
        date(schedule.validTo());
        require(
                schedule.validFrom() == null
                        || schedule.validTo() == null
                        || !schedule.validTo().isBefore(schedule.validFrom()));
        list(schedule.weekWindows(), 100);
        list(schedule.exceptions(), 366);
        var ranges = new ArrayList<int[]>();
        for (var window : schedule.weekWindows()) {
            require(window.weekday() != null && window.weekday() >= 1 && window.weekday() <= 7);
            int n = duration(window.from(), window.to(), window.endDayOffset());
            int start = (window.weekday() - 1) * 1440 + minute(window.from());
            for (var old : ranges)
                for (int shift : new int[] {-10080, 0, 10080})
                    require(!(start < old[1] + shift && start + n > old[0] + shift));
            ranges.add(new int[] {start, start + n});
        }
        var seen = new HashSet<LocalDate>();
        for (var exception : schedule.exceptions()) {
            require(exception.localDate() != null && seen.add(exception.localDate()));
            date(exception.localDate());
            require(
                    (schedule.validFrom() == null
                                    || !exception.localDate().isBefore(schedule.validFrom()))
                            && (schedule.validTo() == null
                                    || !exception.localDate().isAfter(schedule.validTo())));
            if ("CLOSED".equals(exception.mode()))
                require(
                        exception.from() == null
                                && exception.to() == null
                                && Integer.valueOf(0).equals(exception.endDayOffset()));
            else {
                require("OPEN".equals(exception.mode()));
                duration(exception.from(), exception.to(), exception.endDayOffset());
            }
        }
    }

    /**
     * Only refreshable receipts are removed; real source revisions, tax basis and schedule dates
     * stay in content.
     */
    public static Object businessContent(ChannelCatalogDetails details, ObjectMapper json) {
        return stable(json.readValue(json.writeValueAsString(details), Map.class));
    }

    private static Object stable(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach(
                    (key, item) -> {
                        if (!"receiptNo".equals(key)) sorted.put((String) key, stable(item));
                    });
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(CatalogDetailsPolicy::stable).toList();
        return value;
    }
}
