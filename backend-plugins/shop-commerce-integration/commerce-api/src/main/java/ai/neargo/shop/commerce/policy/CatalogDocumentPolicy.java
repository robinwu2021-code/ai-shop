package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.BundleChoice;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.BundleGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Offer;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.OptionChoice;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.OptionGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Price;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Section;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Sku;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 原业务价格不重算；校验完整文档后冻结。哈希仅覆盖稳定业务内容，不含凭证有效期。 */
public final class CatalogDocumentPolicy {
    private CatalogDocumentPolicy() {}

    public static void require(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.CONFLICT);
    }

    private static void text(String s) {
        CommerceValidation.text(s, 128);
    }

    private static <T> List<T> list(List<T> value, int max) {
        require(value != null && value.size() <= max && value.stream().noneMatch(Objects::isNull));
        return value;
    }

    private static void unique(List<String> ids) {
        require(new HashSet<>(ids).size() == ids.size());
        ids.forEach(CatalogDocumentPolicy::text);
    }

    private static void price(Price p, String currency) {
        require(
                p != null
                        && p.amountMinor() >= 0
                        && p.amountMinor() <= 1_000_000_000_000L
                        && currency.equals(p.currency()));
        text(p.priceSourceRef());
        text(p.priceRevision());
        text(p.taxBasisRef());
        text(p.receiptNo());
    }

    private static void range(int min, int max, int choices) {
        require(min >= 0 && max >= min && max <= 1000 && choices > 0);
    }

    public static Document freeze(Document d, List<ValidatedSource> expected, ObjectMapper json) {
        return freezeAt(d, expected, json, Instant.now());
    }

    /**
     * One observation time serves local and remote admission without changing the original default
     * clock.
     */
    public static Document freezeAt(
            Document d, List<ValidatedSource> expected, ObjectMapper json, Instant observedAt) {
        require(
                d != null
                        && d.owner() != null
                        && d.currency() != null
                        && d.currency().matches("[A-Z]{3}")
                        && d.validUntil() != null
                        && d.validUntil().isAfter(observedAt));
        text(d.receiptNo());
        require(expected.equals(d.sources()));
        int schema = version(d);
        if (schema == 2 || schema == 3) {
            require(
                    d.sections() != null
                            && d.sections().isEmpty()
                            && d.offers() != null
                            && d.offers().isEmpty()
                            && d.schedules() != null
                            && d.schedules().isEmpty());
            if (schema == 3 && d.replacement() != null)
                CatalogReplacementPolicy.validate(d, observedAt);
            else require(d.replacement() == null);
            CatalogDetailsPolicy.validate(d);
            if (schema == 3) {
                CatalogAvailabilityPolicy.validate(d, observedAt);
                CatalogTaxBasisPolicy.validate(d);
            }
            String payload = json.writeValueAsString(d);
            require(payload.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024);
            return json.readValue(payload, Document.class);
        }
        require(d.details() == null);
        list(d.sections(), 500);
        list(d.offers(), 10000);
        list(d.schedules(), 500);
        unique(d.sections().stream().map(Section::key).toList());
        unique(d.offers().stream().map(Offer::offerRef).toList());
        var sections = new HashSet<>(d.sections().stream().map(Section::key).toList());
        var offers = new HashSet<>(d.offers().stream().map(Offer::offerRef).toList());
        var edges = new HashMap<String, List<String>>();
        for (var section : d.sections()) {
            text(section.name());
            require(section.source() == null);
        }
        for (var o : d.offers()) {
            text(o.name());
            require(sections.contains(o.sectionKey()));
            require(
                    expected.stream()
                            .anyMatch(
                                    s ->
                                            s.sourceRef().equals(o.sourceRef())
                                                    && s.selectionRevision()
                                                            .equals(o.selectionRevision())));
            require(!list(o.skus(), 100).isEmpty());
            unique(o.skus().stream().map(Sku::skuRef).toList());
            for (var sku : o.skus()) {
                text(sku.name());
                price(sku.price(), d.currency());
                text(sku.inventoryPolicyRef());
            }
            list(o.optionGroups(), 100);
            unique(o.optionGroups().stream().map(OptionGroup::groupRef).toList());
            for (var g : o.optionGroups()) {
                text(g.name());
                list(g.choices(), 100);
                range(g.min(), g.max(), g.choices().size());
                unique(g.choices().stream().map(OptionChoice::choiceRef).toList());
                for (var c : g.choices()) {
                    text(c.name());
                    price(c.price(), d.currency());
                    text(c.inventoryPolicyRef());
                    require(c.min() >= 0 && c.max() >= c.min() && c.max() <= 1000);
                }
            }
            list(o.bundleGroups(), 100);
            unique(o.bundleGroups().stream().map(BundleGroup::groupRef).toList());
            var refs = new ArrayList<String>();
            for (var g : o.bundleGroups()) {
                text(g.name());
                list(g.choices(), 100);
                range(g.min(), g.max(), g.choices().size());
                unique(g.choices().stream().map(BundleChoice::choiceRef).toList());
                for (var c : g.choices()) {
                    require(
                            offers.contains(c.offerRef())
                                    && c.quantity() > 0
                                    && c.quantity() <= 1000);
                    price(c.extraPrice(), d.currency());
                    refs.add(c.offerRef());
                }
            }
            edges.put(o.offerRef(), refs);
            list(o.images(), 10);
            for (var image : o.images()) {
                require(image.sourceRevision() == null);
                text(image.assetNo());
                text(image.receiptNo());
                CommerceValidation.text(image.url(), 2048);
                var uri = URI.create(image.url());
                require(
                        "https".equals(uri.getScheme())
                                && uri.getHost() != null
                                && uri.getUserInfo() == null);
            }
        }
        var done = new HashSet<String>();
        for (String ref : offers) visit(ref, edges, new HashSet<>(), done, 0);
        for (var s : d.schedules()) {
            require(
                    expected.stream()
                            .anyMatch(
                                    v ->
                                            v.sourceRef().equals(s.sourceRef())
                                                    && v.selectionRevision()
                                                            .equals(s.selectionRevision())));
            ZoneId.of(s.timezone());
            list(s.weekdays(), 7);
            require(
                    !s.weekdays().isEmpty()
                            && new HashSet<>(s.weekdays()).size() == s.weekdays().size()
                            && s.weekdays().stream().allMatch(v -> v >= 1 && v <= 7));
            require(s.from() != null && s.to() != null && !s.from().equals(s.to()));
            require(
                    s.validFrom() == null
                            || s.validTo() == null
                            || !s.validTo().isBefore(s.validFrom()));
        }
        String payload = json.writeValueAsString(d);
        require(payload.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024);
        return json.readValue(payload, Document.class);
    }

    /**
     * The original preview additionally binds new tax evidence to its trusted merchant/store
     * context.
     */
    public static Document freeze(
            Document document,
            List<ValidatedSource> expected,
            ObjectMapper json,
            String entity,
            String store) {
        var frozen = freeze(document, expected, json);
        CatalogTaxBasisPolicy.requireScope(frozen, entity, store);
        return frozen;
    }

    private static void visit(
            String ref,
            Map<String, List<String>> edges,
            Set<String> active,
            Set<String> done,
            int depth) {
        require(depth <= 50);
        if (done.contains(ref)) return;
        require(active.add(ref));
        for (String child : edges.get(ref)) visit(child, edges, active, done, depth + 1);
        active.remove(ref);
        done.add(ref);
    }

    /**
     * Absent version belongs to original immutable snapshots; future versions never silently
     * downgrade.
     */
    public static int version(Document document) {
        require(document != null);
        int schema = document.schemaVersion() == null ? 1 : document.schemaVersion();
        require(schema == 1 || schema == 2 || schema == 3);
        require(
                schema == 3
                        || document.availability() == null
                                && document.taxBases() == null
                                && document.replacement() == null);
        return schema;
    }

    public static long itemCount(Document document) {
        return version(document) >= 2
                ? document.details().offers().size()
                : document.offers().size();
    }

    public static String hash(Document d, ObjectMapper json) {
        if (version(d) == 3) return CatalogV3Policy.hash(d, json);
        if (version(d) == 2)
            return ChannelIdentity.hash(
                    json.writeValueAsString(
                            List.of(
                                    2,
                                    d.owner(),
                                    d.currency(),
                                    d.sources().stream()
                                            .map(
                                                    source ->
                                                            List.of(
                                                                    source.owner(),
                                                                    source.sourceRef(),
                                                                    source.selectionRevision(),
                                                                    source.priority(),
                                                                    source.proof().contentHash()))
                                            .toList(),
                                    CatalogDetailsPolicy.businessContent(d.details(), json))));
        // Receipts, prices, selection revisions remain part of content; refreshed validation TTL is
        // not menu content.
        return ChannelIdentity.hash(
                json.writeValueAsString(
                        List.of(
                                d.owner(),
                                d.currency(),
                                d.sources().stream()
                                        .map(
                                                s ->
                                                        List.of(
                                                                s.owner(),
                                                                s.sourceRef(),
                                                                s.selectionRevision(),
                                                                s.priority(),
                                                                s.proof().contentHash()))
                                        .toList(),
                                d.sections(),
                                d.offers(),
                                d.schedules())));
    }
}
