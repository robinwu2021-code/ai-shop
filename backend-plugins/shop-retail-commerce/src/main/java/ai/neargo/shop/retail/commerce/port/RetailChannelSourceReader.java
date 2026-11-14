package ai.neargo.shop.retail.commerce.port;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogTaxBasisPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderSourceEvidencePolicy;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort.Resolution;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.RetailProductSourcePort;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Original supplier invoices are interpreted against actual immutable retail selections and
 * historical product owners.
 */
public final class RetailChannelSourceReader {
    private final ChannelOrderSourceEvidencePort evidence;
    private final RetailSelectionMapper selections;
    private final RetailProductSourcePort products;
    private final MaterialCatalogQueryPort materials;
    private final ObjectMapper json;
    private final Clock clock;

    public RetailChannelSourceReader(
            ChannelOrderSourceEvidencePort evidence,
            RetailSelectionMapper selections,
            RetailProductSourcePort products,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            Clock clock) {
        this.evidence = evidence;
        this.selections = selections;
        this.products = products;
        this.materials = materials;
        this.json = json;
        this.clock = clock;
    }

    public record Line(
            ChannelOrderGateway.Line invoice,
            String goodsNo,
            String skuNo,
            String unit,
            ChannelCatalogDetails.Offer offer,
            ChannelCatalogDetails.Variant variant) {}

    public record Original(
            ChannelOrderSourceEvidencePort.Evidence evidence,
            ChannelOrderSourceEvidencePort.PublishedCatalog publication,
            List<Line> lines,
            List<RetailSelection> selections,
            List<RetailProductSourcePort.Facts> productProofs,
            Instant validUntil) {
        public Original {
            lines = List.copyOf(lines);
            selections = List.copyOf(selections);
            productProofs = List.copyOf(productProofs);
        }
    }

    public ChannelOrderSourceEvidencePort.Evidence current(
            ChannelOrderIngestPort.Reference reference) {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        var query =
                new ChannelOrderSourceEvidencePort.Query(
                        reference.entityNo(),
                        reference.storeNo(),
                        reference.importNo(),
                        Owner.RETAIL,
                        reference.documentHash());
        return ChannelOrderSourceEvidencePolicy.validate(query, evidence.read(query), clock);
    }

    public Original original(ChannelOrderIngestPort.Reference reference) {
        var actual = current(reference);
        var query = actual.scope();
        var command = actual.original();
        var original = command.original();
        var document = original.document();
        require(document.requestedAt() == null || !document.requestedAt().isAfter(clock.instant()));
        require(original.observedAt() != null);
        ChannelOrderPolicy.validate(
                new ChannelOrderGateway.Query(
                        query.entityNo(),
                        query.storeNo(),
                        actual.routing().connectionNo(),
                        actual.routing().profileNo(),
                        document.externalAccountRef(),
                        document.externalStoreRef(),
                        document.externalOrderRef(),
                        null,
                        null,
                        null),
                original,
                Clock.fixed(original.observedAt(), ZoneOffset.UTC));
        var publication = evidence.publishedCatalog(query);
        require(
                publication != null
                        && query.equals(publication.scope())
                        && publication.document() != null
                        && publication.document().owner() == Owner.RETAIL
                        && text(publication.originalPublicationReceiptRef())
                        && text(publication.runNo())
                        && text(publication.snapshotNo())
                        && text(publication.operationNo())
                        && text(publication.attemptRef())
                        && document.catalogHash().equals(publication.catalogHash()));
        var catalog = publication.document();
        require(
                Integer.valueOf(3).equals(catalog.schemaVersion())
                        && catalog.details() != null
                        && document.catalogHash().equals(CatalogDocumentPolicy.hash(catalog, json))
                        && document.currency().equals(catalog.currency())
                        && command.lines().size() == document.lines().size());
        CatalogTaxBasisPolicy.validate(catalog);
        CatalogTaxBasisPolicy.requireScope(catalog, query.entityNo(), query.storeNo());
        require(
                catalog.taxBases().stream()
                        .allMatch(t -> actual.routing().market().equals(t.market().market())));
        var lines = new ArrayList<Line>();
        var selected = new ArrayList<RetailSelection>();
        var proofs = new ArrayList<RetailProductSourcePort.Facts>();
        var seen = new HashSet<String>();
        Instant deadline = actual.current().validUntil();
        for (var mapped : command.lines()) {
            require(seen.add(mapped.externalLineRef()) && mapped.selections().isEmpty());
            var matches =
                    document.lines().stream()
                            .filter(l -> mapped.externalLineRef().equals(l.lineRef()))
                            .toList();
            require(matches.size() == 1);
            var line = matches.getFirst();
            require(
                    line.selections().isEmpty()
                            && mapped.quantity().equals(line.quantity())
                            && mapped.unit().equals(line.unit())
                            && mapped.offer().identity().equals(line.offerIdentity())
                            && mapped.sku().identity().equals(line.skuIdentity()));
            var offer = mapped.offer();
            var sku = mapped.sku();
            mapping(offer, ObjectType.OFFER);
            mapping(sku, ObjectType.SKU);
            require(
                    offer.sourceContext().menuRef().equals(sku.sourceContext().menuRef())
                            && offer.sourceRevision().equals(sku.sourceRevision())
                            && offer.sourceRef().equals(sku.salesOfferRef())
                            && offer.sourceRef().equals(offer.salesOfferRef()));
            var selection =
                    selections.revision(
                            query.entityNo(),
                            query.storeNo(),
                            offer.sourceContext().menuRef(),
                            offer.sourceRevision());
            require(selection != null);
            selection.validate();
            require(
                    query.entityNo().equals(selection.entityNo())
                            && query.storeNo().equals(selection.storeNo())
                            && command.connectionNo().equals(selection.connectionNo())
                            && command.connectionRevisionNo()
                                    .equals(selection.connectionRevisionNo())
                            && selection.contentHash().equals(offer.sourceHash())
                            && selection.contentHash().equals(sku.sourceHash())
                            && catalog.sources().stream()
                                            .filter(
                                                    s ->
                                                            Owner.RETAIL == s.owner()
                                                                    && selection
                                                                            .selectionNo()
                                                                            .equals(s.sourceRef())
                                                                    && selection
                                                                            .revisionNo()
                                                                            .equals(
                                                                                    s
                                                                                            .selectionRevision())
                                                                    && selection
                                                                            .contentHash()
                                                                            .equals(
                                                                                    s.proof()
                                                                                            .contentHash()))
                                            .count()
                                    == 1);
            var entries =
                    selection.entries().stream()
                            .filter(
                                    e ->
                                            offer.sourceRef().equals(e.goodsNo())
                                                    && e.skuNos().contains(sku.sourceRef()))
                            .toList();
            require(entries.size() == 1);
            var entry = entries.getFirst();
            var productQuery =
                    new RetailProductSourcePort.Query(
                            query.entityNo(),
                            query.storeNo(),
                            entry.goodsNo(),
                            entry.goodsRevision(),
                            true);
            var product = products.read(productQuery);
            require(
                    product != null
                            && productQuery.equals(product.query())
                            && product.revisionNo() == entry.goodsRevision()
                            && entry.goodsContentHash().equals(product.contentHash())
                            && product.publishedSkuNos().contains(sku.sourceRef())
                            && product.verifiedAt() != null
                            && !product.verifiedAt().isAfter(clock.instant())
                            && product.validUntil() != null
                            && product.validUntil().isAfter(clock.instant())
                            && !product.validUntil().isAfter(product.verifiedAt().plusSeconds(30))
                            && text(product.receiptNo()));
            if (product.validUntil().isBefore(deadline)) deadline = product.validUntil();
            var catalogOffers =
                    catalog.details().offers().stream()
                            .filter(
                                    o ->
                                            selection.selectionNo().equals(o.sourceRef())
                                                    && selection
                                                            .revisionNo()
                                                            .equals(o.selectionRevision())
                                                    && entry.goodsNo().equals(o.goodsRef())
                                                    && entry.goodsNo().equals(o.originalOfferRef()))
                            .toList();
            require(catalogOffers.size() == 1);
            var publishedOffer = catalogOffers.getFirst();
            var variants =
                    publishedOffer.variants().stream()
                            .filter(v -> sku.sourceRef().equals(v.skuRef()))
                            .toList();
            require(variants.size() == 1);
            var variant = variants.getFirst();
            require(
                    variant.optionGroups().isEmpty()
                            && variant.bundleGroups().isEmpty()
                            && variant.price() != null
                            && variant.price().amountMinor() != null
                            && variant.price().amountMinor() >= 0
                            && document.currency().equals(variant.price().currency())
                            && text(variant.price().receiptNo()));
            var found =
                    materials.getSkuMaterials(
                            query.entityNo(), query.storeNo(), List.of(sku.sourceRef()));
            require(
                    found != null
                            && found.size() == 1
                            && sku.sourceRef().equals(found.getFirst().skuNo()));
            var material = found.getFirst().material();
            var quantity = new BigDecimal(line.quantity());
            require(
                    MaterialCatalogFacts.validFact(material)
                            && query.entityNo().equals(material.entityNo())
                            && SourceQuantityUnits.same(line.unit(), material.baseUom())
                            && (quantity.stripTrailingZeros().scale() <= 0
                                    || Integer.valueOf(1).equals(material.weighed())));
            lines.add(
                    new Line(
                            line,
                            entry.goodsNo(),
                            sku.sourceRef(),
                            material.baseUom(),
                            publishedOffer,
                            variant));
            if (!selected.contains(selection)) selected.add(selection);
            proofs.add(product);
        }
        require(deadline.isAfter(clock.instant()));
        return new Original(actual, publication, lines, selected, proofs, deadline);
    }

    private static void mapping(Resolution resolution, ObjectType type) {
        require(
                resolution != null
                        && resolution.sourceOwner() == Owner.RETAIL
                        && resolution.objectType() == type
                        && resolution.sourceContext() != null
                        && resolution.sourceContext().mode() == ReadMode.HISTORICAL
                        && resolution.sourceContext().optionGroupRef() == null
                        && resolution.sourceContext().variantSkuRef() == null
                        && resolution.sourceContext().bundleGroupRef() == null
                        && text(resolution.historicalExternalEvidenceRef())
                        && text(resolution.freshSourceReceiptNo()));
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException(
                    "Missing or inconsistent original RETAIL source evidence");
    }
}
