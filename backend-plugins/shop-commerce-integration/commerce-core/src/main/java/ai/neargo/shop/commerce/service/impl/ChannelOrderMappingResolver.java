package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimResourceMappingRevision;
import ai.neargo.shop.commerce.policy.CatalogResourceIndex;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.MappedLine;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.MappedSelection;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Explicit original catalog hash selects historical membership; the latest mapping pointer is never
 * an order price source.
 */
public final class ChannelOrderMappingResolver {
    private final MappingPersistence data;
    private final ChannelResourceMappingPort authority;
    private final HistoricalManagedMappingService history;

    public ChannelOrderMappingResolver(
            MappingPersistence data, ChannelResourceMappingPort authority) {
        this(data, authority, null);
    }

    public ChannelOrderMappingResolver(
            MappingPersistence data,
            ChannelResourceMappingPort authority,
            HistoricalManagedMappingService history) {
        this.data = data;
        this.authority = authority;
        this.history = history;
    }

    /**
     * Resolves every distinct identity once, with original owner calls outside the commerce read
     * transaction.
     */
    public List<MappedLine> resolve(CimOrderLink order, ChannelOrderGateway.Document original) {
        var catalog = data.db.commands.query(() -> catalog(order, original.catalogHash()));
        var resources = CatalogResourceIndex.resources(catalog);
        var session = history == null ? null : history.session(order, catalog);
        var sourceHashes =
                catalog.sources().stream()
                        .collect(
                                Collectors.toMap(
                                        source -> source.sourceRef(),
                                        source -> source.proof().contentHash()));
        var cache = new HashMap<Lookup, ChannelResourceMappingPort.Resolution>();
        return original.lines().stream()
                .map(
                        line -> {
                            var offer =
                                    resolve(
                                            order,
                                            resources,
                                            new Lookup(ObjectType.OFFER, line.offerIdentity()),
                                            cache,
                                            sourceHashes,
                                            session);
                            var sku =
                                    resolve(
                                            order,
                                            resources,
                                            new Lookup(ObjectType.SKU, line.skuIdentity()),
                                            cache,
                                            sourceHashes,
                                            session);
                            require(
                                    Objects.equals(offer.salesOfferRef(), sku.salesOfferRef())
                                            && offer.sourceContext() != null
                                            && sku.sourceContext() != null
                                            && offer.sourceContext()
                                                    .menuRef()
                                                    .equals(sku.sourceContext().menuRef()));
                            var choices =
                                    line.selections().stream()
                                            .map(
                                                    selection -> {
                                                        ObjectType type =
                                                                ObjectType.valueOf(
                                                                        selection.kind());
                                                        require(
                                                                type == ObjectType.OPTION
                                                                        || type == ObjectType.SKU
                                                                        || type
                                                                                == ObjectType
                                                                                        .BUNDLE_MEMBER);
                                                        var mapping =
                                                                resolve(
                                                                        order,
                                                                        resources,
                                                                        new Lookup(
                                                                                type,
                                                                                selection
                                                                                        .externalIdentity()),
                                                                        cache,
                                                                        sourceHashes,
                                                                        session);
                                                        if (session != null) {
                                                            var selectedResource =
                                                                    session.resource(
                                                                            type,
                                                                            selection
                                                                                    .externalIdentity());
                                                            var skuResource =
                                                                    session.resource(
                                                                            ObjectType.SKU,
                                                                            line.skuIdentity());
                                                            boolean sameVariant =
                                                                    selectedResource
                                                                                    .key()
                                                                                    .equals(
                                                                                            skuResource
                                                                                                    .key())
                                                                            || selectedResource
                                                                                    .parents()
                                                                                    .contains(
                                                                                            skuResource
                                                                                                    .key())
                                                                            || resources.stream()
                                                                                    .anyMatch(
                                                                                            parent ->
                                                                                                    selectedResource
                                                                                                                    .parents()
                                                                                                                    .contains(
                                                                                                                            parent
                                                                                                                                    .key())
                                                                                                            && parent.key()
                                                                                                                            .type()
                                                                                                                    == ObjectType
                                                                                                                            .OPTION_GROUP
                                                                                                            && parent.parents()
                                                                                                                    .contains(
                                                                                                                            skuResource
                                                                                                                                    .key()));
                                                            require(sameVariant);
                                                        }
                                                        require(
                                                                Objects.equals(
                                                                                offer
                                                                                        .salesOfferRef(),
                                                                                mapping
                                                                                        .salesOfferRef())
                                                                        && mapping.sourceContext()
                                                                                != null
                                                                        && offer.sourceContext()
                                                                                .menuRef()
                                                                                .equals(
                                                                                        mapping.sourceContext()
                                                                                                .menuRef()));
                                                        return new MappedSelection(
                                                                selection.lineRef(),
                                                                selection.kind(),
                                                                mapping,
                                                                selection.quantity(),
                                                                selection.amountMinor());
                                                    })
                                            .toList();
                            return new MappedLine(
                                    line.lineRef(),
                                    offer,
                                    sku,
                                    line.quantity(),
                                    line.unit(),
                                    choices);
                        })
                .toList();
    }

    private record Lookup(ObjectType type, Identity identity) {}

    private ChannelResourceMappingPort.Resolution resolve(
            CimOrderLink order,
            List<CatalogResourceIndex.Resource> resources,
            Lookup lookup,
            HashMap<Lookup, ChannelResourceMappingPort.Resolution> cache,
            Map<String, String> sourceHashes,
            HistoricalManagedMappingService.Session session) {
        var cached = cache.get(lookup);
        if (cached != null) return cached;
        var expected =
                session == null
                        ? resources.stream()
                                .filter(resource -> resource.key().type() == lookup.type())
                                .toList()
                        : List.of(session.resource(lookup.type(), lookup.identity()));
        var reference =
                data.db.commands.query(
                        () -> {
                            var c =
                                    data.db.connection(
                                            order.getEntityNo(),
                                            order.getStoreNo(),
                                            order.getConnectionNo(),
                                            false,
                                            false);
                            String hash = data.hash(c, lookup.type(), lookup.identity());
                            var mapping =
                                    data.mappings.selectOne(
                                            Wrappers.<CimResourceMapping>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", order.getEntityNo())
                                                    .eq("store_no", order.getStoreNo())
                                                    .eq("connection_no", order.getConnectionNo())
                                                    .eq("object_type", lookup.type().name())
                                                    .eq("external_identity_hash", hash));
                            if (mapping == null) return null;
                            require(data.identity(mapping).equals(lookup.identity()));
                            var revisions =
                                    data.revisions.selectList(
                                            Wrappers.<CimResourceMappingRevision>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", order.getEntityNo())
                                                    .eq("mapping_no", mapping.getMappingNo())
                                                    .eq("validation_status", "VERIFIED")
                                                    .eq("source_owner", order.getSourceOwner())
                                                    .in(
                                                            "source_ref",
                                                            expected.stream()
                                                                    .map(
                                                                            CatalogResourceIndex
                                                                                            .Resource
                                                                                    ::sourceRef)
                                                                    .distinct()
                                                                    .toList())
                                                    .in(
                                                            "source_revision_ref",
                                                            expected.stream()
                                                                    .map(
                                                                            CatalogResourceIndex
                                                                                            .Resource
                                                                                    ::revision)
                                                                    .distinct()
                                                                    .toList())
                                                    .in(
                                                            "source_content_hash",
                                                            expected.stream()
                                                                    .map(
                                                                            resource ->
                                                                                    sourceHashes
                                                                                            .get(
                                                                                                    resource
                                                                                                                            .context()
                                                                                                                    == null
                                                                                                            ? resource
                                                                                                                    .sourceRef()
                                                                                                            : resource.context()
                                                                                                                    .menuRef()))
                                                                    .distinct()
                                                                    .toList())
                                                    .orderByDesc("revision")
                                                    .last("LIMIT 101"));
                            var revision =
                                    revisions.stream()
                                            .filter(
                                                    r ->
                                                            expected.stream()
                                                                    .anyMatch(
                                                                            resource ->
                                                                                    resource.key()
                                                                                                            .type()
                                                                                                    == lookup
                                                                                                            .type()
                                                                                            && resource.owner()
                                                                                                    .name()
                                                                                                    .equals(
                                                                                                            r
                                                                                                                    .getSourceOwner())
                                                                                            && resource.sourceRef()
                                                                                                    .equals(
                                                                                                            r
                                                                                                                    .getSourceRef())
                                                                                            && resource.revision()
                                                                                                    .equals(
                                                                                                            r
                                                                                                                    .getSourceRevisionRef())
                                                                                            && Objects
                                                                                                    .equals(
                                                                                                            resource
                                                                                                                    .salesOfferRef(),
                                                                                                            r
                                                                                                                    .getSalesOfferRef())
                                                                                            && Objects
                                                                                                    .equals(
                                                                                                            resource
                                                                                                                    .context(),
                                                                                                            data
                                                                                                                    .context(
                                                                                                                            r))
                                                                                            && Objects
                                                                                                    .equals(
                                                                                                            r
                                                                                                                    .getSourceContentHash(),
                                                                                                            sourceHashes
                                                                                                                    .get(
                                                                                                                            resource
                                                                                                                                                    .context()
                                                                                                                                            == null
                                                                                                                                    ? resource
                                                                                                                                            .sourceRef()
                                                                                                                                    : resource.context()
                                                                                                                                            .menuRef()))))
                                            .findFirst()
                                            .orElse(null);
                            if (revision == null) return null;
                            return new ChannelResourceMappingPort.Reference(
                                    order.getEntityNo(),
                                    order.getStoreNo(),
                                    order.getConnectionNo(),
                                    mapping.getMappingNo(),
                                    revision.getRevisionNo());
                        });
        if (reference == null) {
            require(session != null);
            reference = session.resolve(lookup.type(), lookup.identity());
        }
        var result = authority.resolveHistorical(reference);
        cache.put(lookup, result);
        return result;
    }

    private Document catalog(CimOrderLink order, String hash) {
        return data.db.publishedCatalog(order, hash).document();
    }
}
