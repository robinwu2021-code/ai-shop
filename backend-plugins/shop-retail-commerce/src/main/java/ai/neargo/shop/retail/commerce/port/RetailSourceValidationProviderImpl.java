package ai.neargo.shop.retail.commerce.port;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import ai.neargo.shop.spi.product.RetailProductSourcePort;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * RETAIL proofs use explicit selection parents, original published revisions and exact sales
 * membership.
 */
public final class RetailSourceValidationProviderImpl implements ChannelSourceValidationProvider {
    private final RetailSelectionMapper selections;
    private final RetailProductSourcePort products;
    private final Clock clock;

    public RetailSourceValidationProviderImpl(
            RetailSelectionMapper selections, RetailProductSourcePort products, Clock clock) {
        this.selections = selections;
        this.products = products;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.RETAIL;
    }

    @Override
    public Validation validate(Source source) {
        CatalogSourceTransportPolicy.validateSource(source);
        outsideTransaction();
        if (source.owner() != Owner.RETAIL
                || !List.of(Kind.SELECTION, Kind.GOODS, Kind.OFFER, Kind.SKU)
                        .contains(source.kind())) return status(Status.UNSUPPORTED);
        boolean root = source.kind() == Kind.SELECTION;
        var context = source.context();
        if (root && source.salesOfferRef() != null
                || !root && context == null
                || context != null
                        && (context.optionGroupRef() != null
                                || context.variantSkuRef() != null
                                || context.bundleGroupRef() != null))
            return status(Status.UNSUPPORTED);
        String parent = context == null ? source.sourceRef() : context.menuRef();
        if (root && !parent.equals(source.sourceRef())) return status(Status.UNSUPPORTED);
        boolean historical = context != null && context.mode() == ReadMode.HISTORICAL;
        var selection =
                selections.revision(
                        source.entityNo(), source.storeNo(), parent, source.sourceRevision());
        if (selection == null) return status(Status.UNKNOWN);
        selection.validate();
        if (!historical
                && !selection.equals(
                        selections.current(source.entityNo(), source.storeNo(), parent)))
            return status(Status.STALE);
        var matching =
                selection.entries().stream()
                        .filter(
                                e ->
                                        switch (source.kind()) {
                                            case SELECTION -> true;
                                            case GOODS, OFFER ->
                                                    source.sourceRef().equals(e.goodsNo());
                                            case SKU -> e.skuNos().contains(source.sourceRef());
                                            default -> false;
                                        })
                        .toList();
        if (matching.isEmpty() || !root && matching.size() != 1) return status(Status.UNKNOWN);
        if (!root
                && source.salesOfferRef() != null
                && !source.salesOfferRef().equals(matching.getFirst().goodsNo()))
            return status(Status.UNKNOWN);
        Instant until = clock.instant().plusSeconds(30);
        for (var entry : matching) {
            var q =
                    new RetailProductSourcePort.Query(
                            source.entityNo(),
                            source.storeNo(),
                            entry.goodsNo(),
                            entry.goodsRevision(),
                            historical);
            var facts = products.read(q);
            validateProduct(q, facts, clock.instant());
            if (!entry.goodsContentHash().equals(facts.contentHash())
                    || !facts.publishedSkuNos().containsAll(entry.skuNos()))
                return status(Status.STALE);
            if (facts.validUntil().isBefore(until)) until = facts.validUntil();
        }
        if (!historical
                && !selection.equals(
                        selections.current(source.entityNo(), source.storeNo(), parent)))
            return status(Status.STALE);
        var proof =
                new Validation(
                        Status.VALID,
                        source.sourceRef(),
                        selection.revisionNo(),
                        selection.contentHash(),
                        until,
                        PricingSnapshotHash.of(List.of(source, selection.contentHash(), until)),
                        source.salesOfferRef(),
                        context);
        CatalogSourceTransportPolicy.validateProof(source, proof, clock);
        return proof;
    }

    @Override
    public Validation current(Source source) {
        CatalogSourceTransportPolicy.validateSource(source);
        outsideTransaction();
        if (source.owner() != Owner.RETAIL
                || source.kind() != Kind.SELECTION
                || source.context() != null
                || source.salesOfferRef() != null) return status(Status.UNSUPPORTED);
        var selection = selections.current(source.entityNo(), source.storeNo(), source.sourceRef());
        return selection == null
                ? status(Status.UNKNOWN)
                : validate(
                        new Source(
                                source.entityNo(),
                                source.storeNo(),
                                Owner.RETAIL,
                                Kind.SELECTION,
                                source.sourceRef(),
                                selection.revisionNo()));
    }

    public static void validateProduct(
            RetailProductSourcePort.Query q, RetailProductSourcePort.Facts f, Instant now) {
        if (f == null
                || !q.equals(f.query())
                || f.revisionNo() <= 0
                || q.revisionNo() != null && f.revisionNo() != q.revisionNo()
                || f.contentHash() == null
                || !f.contentHash().matches("[a-f0-9]{64}")
                || f.verifiedAt() == null
                || f.verifiedAt().isAfter(now)
                || f.validUntil() == null
                || !f.validUntil().isAfter(now)
                || f.validUntil().isAfter(f.verifiedAt().plusSeconds(30))
                || f.receiptNo() == null
                || f.receiptNo().isBlank())
            throw new IllegalStateException("Invalid original product evidence");
    }

    public static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Retail owner reads require no local transaction");
    }

    private static Validation status(Status status) {
        return new Validation(status, null, null, null, null, null);
    }
}
