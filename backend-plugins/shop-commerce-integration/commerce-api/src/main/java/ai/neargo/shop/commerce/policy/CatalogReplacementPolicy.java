package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.spi.commerce.CatalogReplacement;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

/**
 * Legacy positive proofs remain unchanged; this additional branch requires genuine removal and
 * scope authority.
 */
public final class CatalogReplacementPolicy {
    private CatalogReplacementPolicy() {}

    public static void validate(Document document, Instant now) {
        var r = document.replacement();
        if (r == null) return;
        require(
                Integer.valueOf(3).equals(document.schemaVersion())
                        && r.scopeFacts() != null
                        && r.authority() != null
                        && r.withdrawals() != null
                        && r.withdrawals().size() <= 100);
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var scope = r.scopeFacts().scope();
        CatalogPriceSourceFacts.validateScopeFacts(scope, r.scopeFacts(), now);
        ChannelCatalogAuthorityPolicy.validate(r.authority().query(), r.authority(), now);
        require(
                scope.entityNo().equals(r.authority().query().entityNo())
                        && scope.storeNo().equals(r.authority().query().storeNo())
                        && document.owner().name().equals(r.authority().connection().sourceOwner())
                        && r.scopeFacts().market().market().equals(r.authority().profile().market())
                        && document.currency().equals(r.scopeFacts().market().currency())
                        && !document.validUntil().isAfter(r.scopeFacts().validUntil())
                        && !document.validUntil().isAfter(r.authority().validUntil()));
        var seen = new HashSet<String>();
        document.sources().forEach(s -> require(seen.add(s.sourceRef())));
        for (var proof : r.withdrawals()) {
            require(proof != null && proof.data() != null);
            var q = proof.data().query();
            SourceWithdrawalPolicy.validate(q, proof, clock);
            require(
                    scope.entityNo().equals(q.entityNo())
                            && scope.storeNo().equals(q.storeNo())
                            && document.owner() == q.owner()
                            && seen.add(q.sourceRef())
                            && !document.validUntil().isAfter(proof.validUntil()));
        }
        require(!document.sources().isEmpty() || !r.withdrawals().isEmpty());
        for (var tax : document.taxBases())
            require(
                    scope.entityNo().equals(tax.entityNo())
                            && scope.storeNo().equals(tax.storeNo())
                            && tax.market().equals(r.scopeFacts().market())
                            && tax.tax().equals(r.scopeFacts().tax()));
        if (document.details().offers().isEmpty()) require(document.taxBases().size() == 1);
    }

    /**
     * Refreshable transport times/receipts do not change the immutable replacement business
     * revision.
     */
    public static Object business(CatalogReplacement r) {
        return List.of(
                r.scopeFacts().scope(),
                r.scopeFacts().market(),
                r.scopeFacts().tax(),
                r.authority().query(),
                r.authority().provider(),
                r.authority().profile(),
                r.authority().account(),
                r.authority().connection(),
                r.authority().grant(),
                r.withdrawals().stream().map(p -> p.data()).toList());
    }

    private static void require(boolean value) {
        CatalogDocumentPolicy.require(value);
    }
}
