package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Stable bounded protocol keys retain original parents without splitting user identifiers or
 * guessing a menu.
 */
public final class CatalogResourceIndex {
    private CatalogResourceIndex() {}

    public record Key(ObjectType type, String value) {}

    public record Resource(
            Key key,
            Owner owner,
            String sourceRef,
            String revision,
            String salesOfferRef,
            SourceContext context,
            List<Key> parents) {
        public Resource {
            parents = List.copyOf(parents);
        }
    }

    public static String child(ObjectType type, String... refs) {
        var parts = new ArrayList<String>();
        parts.add(type.name());
        parts.addAll(List.of(refs));
        return PricingSnapshotHash.of(parts);
    }

    /**
     * Legacy documents without explicit original identity cannot create controlled MANAGED
     * mappings.
     */
    public static List<Resource> resources(Document d) {
        if (d.schemaVersion() != 3 || d.details() == null)
            throw new IllegalArgumentException("Managed mappings require a lossless v3 document");
        var result = new ArrayList<Resource>();
        for (var source : d.sources())
            result.add(
                    new Resource(
                            new Key(ObjectType.MENU, source.sourceRef()),
                            d.owner(),
                            source.sourceRef(),
                            source.selectionRevision(),
                            null,
                            null,
                            List.of()));
        for (var section : d.details().sections()) {
            var source = section.source();
            if (source == null) {
                if (d.owner() != Owner.RETAIL)
                    throw new IllegalArgumentException("Missing explicit category origin");
                continue; // Retail presentation grouping is not a claimed original category
                // resource.
            }
            result.add(
                    new Resource(
                            new Key(ObjectType.CATEGORY, section.key()),
                            d.owner(),
                            source.categoryRef(),
                            source.selectionRevision(),
                            null,
                            new SourceContext(source.sourceRef(), null, ReadMode.CURRENT),
                            List.of(new Key(ObjectType.MENU, source.sourceRef()))));
        }
        for (var offer : d.details().offers()) {
            if (offer.originalOfferRef() == null)
                throw new IllegalArgumentException("Missing original offer identity");
            var sale = new Key(ObjectType.OFFER, offer.offerRef());
            var section =
                    d.details().sections().stream()
                            .filter(s -> s.key().equals(offer.sectionKey()))
                            .findFirst()
                            .orElseThrow();
            var parent =
                    section.source() == null
                            ? List.of(new Key(ObjectType.MENU, offer.sourceRef()))
                            : List.of(
                                    new Key(ObjectType.MENU, offer.sourceRef()),
                                    new Key(ObjectType.CATEGORY, offer.sectionKey()));
            var context = new SourceContext(offer.sourceRef(), null, ReadMode.CURRENT);
            result.add(
                    new Resource(
                            sale,
                            d.owner(),
                            offer.originalOfferRef(),
                            offer.selectionRevision(),
                            offer.originalOfferRef(),
                            context,
                            parent));
            for (var sku : offer.variants()) {
                var skuKey =
                        new Key(
                                ObjectType.SKU,
                                child(ObjectType.SKU, offer.offerRef(), sku.skuRef()));
                result.add(
                        new Resource(
                                skuKey,
                                d.owner(),
                                sku.skuRef(),
                                offer.selectionRevision(),
                                offer.originalOfferRef(),
                                context,
                                List.of(sale)));
                for (var group : sku.optionGroups()) {
                    var groupKey =
                            new Key(
                                    ObjectType.OPTION_GROUP,
                                    child(
                                            ObjectType.OPTION_GROUP,
                                            offer.offerRef(),
                                            sku.skuRef(),
                                            group.groupRef()));
                    result.add(
                            new Resource(
                                    groupKey,
                                    d.owner(),
                                    group.groupRef(),
                                    offer.selectionRevision(),
                                    offer.originalOfferRef(),
                                    context,
                                    List.of(sale, skuKey)));
                    for (var choice : group.choices())
                        result.add(
                                new Resource(
                                        new Key(
                                                ObjectType.OPTION,
                                                child(
                                                        ObjectType.OPTION,
                                                        offer.offerRef(),
                                                        sku.skuRef(),
                                                        group.groupRef(),
                                                        choice.choiceRef())),
                                        d.owner(),
                                        choice.choiceRef(),
                                        offer.selectionRevision(),
                                        offer.originalOfferRef(),
                                        new SourceContext(
                                                offer.sourceRef(),
                                                group.groupRef(),
                                                ReadMode.CURRENT),
                                        List.of(sale, groupKey)));
                }
                for (var group : sku.bundleGroups()) {
                    var groupKey =
                            new Key(
                                    ObjectType.BUNDLE_GROUP,
                                    child(
                                            ObjectType.BUNDLE_GROUP,
                                            offer.offerRef(),
                                            sku.skuRef(),
                                            group.groupRef()));
                    var bundleContext =
                            new SourceContext(
                                    offer.sourceRef(), null, ReadMode.CURRENT, sku.skuRef(), null);
                    result.add(
                            new Resource(
                                    groupKey,
                                    d.owner(),
                                    group.groupRef(),
                                    offer.selectionRevision(),
                                    offer.originalOfferRef(),
                                    bundleContext,
                                    List.of(sale, skuKey)));
                    for (var choice : group.choices())
                        result.add(
                                new Resource(
                                        new Key(
                                                ObjectType.BUNDLE_MEMBER,
                                                child(
                                                        ObjectType.BUNDLE_MEMBER,
                                                        offer.offerRef(),
                                                        sku.skuRef(),
                                                        group.groupRef(),
                                                        choice.choiceRef())),
                                        d.owner(),
                                        choice.choiceRef(),
                                        offer.selectionRevision(),
                                        offer.originalOfferRef(),
                                        new SourceContext(
                                                offer.sourceRef(),
                                                null,
                                                ReadMode.CURRENT,
                                                sku.skuRef(),
                                                group.groupRef()),
                                        List.of(sale, skuKey, groupKey)));
                }
            }
        }
        var seen = new HashSet<Key>();
        for (var resource : result) {
            if (resource.key().value().length() > 128
                    || !seen.add(resource.key())
                    || resource.parents().stream().anyMatch(parent -> !seen.contains(parent)))
                throw new IllegalArgumentException("Invalid catalog resource order or identity");
        }
        return List.copyOf(result);
    }
}
