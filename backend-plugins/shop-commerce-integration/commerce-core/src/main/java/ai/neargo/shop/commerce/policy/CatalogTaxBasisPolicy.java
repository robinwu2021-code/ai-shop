package ai.neargo.shop.commerce.policy;

import static ai.neargo.shop.commerce.policy.CatalogDocumentPolicy.require;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import java.util.HashSet;
import java.util.Currency;
import java.util.Objects;

/** Every displayed amount needs an original, scoped, explainable tax definition without channel-side calculation. */
public final class CatalogTaxBasisPolicy {
    private CatalogTaxBasisPolicy() { }

    /** Definition validation delegates to the original tax owner policy, including its source hash. */
    public static void validate(Document document) {
        require(document.taxBases() != null && document.taxBases().size() <= 1000);
        Currency.getInstance(document.currency());
        var refs = new HashSet<String>();
        CatalogTaxBasis first = null;
        for (var basis : document.taxBases()) {
            require(basis != null); CommerceValidation.text(basis.taxBasisRef(), 128);
            require(refs.add(basis.taxBasisRef()));
            CatalogPriceSourceFacts.validateMarket(basis.market());
            CatalogPriceSourceFacts.taxSnapshotForScope(
                    new MarketScope(basis.entityNo(), basis.storeNo()), basis.tax());
            require(document.currency().equals(basis.market().currency()));
            if (first != null) require(first.entityNo().equals(basis.entityNo())
                    && first.storeNo().equals(basis.storeNo()) && first.market().equals(basis.market()));
            first = basis;
        }
        var used = new HashSet<String>();
        for (var offer : document.details().offers()) for (var variant : offer.variants()) {
            used.add(variant.price().taxBasisRef());
            for (var group : variant.optionGroups()) for (var choice : group.choices())
                used.add(choice.price().taxBasisRef());
            for (var group : variant.bundleGroups()) for (var member : group.choices())
                used.add(member.extraPrice().taxBasisRef());
        }
        require(refs.equals(used));
    }

    /** Preview knows the real caller scope and must not accept another store's self-consistent tax definitions. */
    public static void requireScope(Document document, String entity, String store) {
        if (CatalogDocumentPolicy.version(document) != 3) return;
        CommerceValidation.text(entity, 32); CommerceValidation.text(store, 32);
        for (var basis : document.taxBases()) require(Objects.equals(entity, basis.entityNo())
                && Objects.equals(store, basis.storeNo()));
    }
}
