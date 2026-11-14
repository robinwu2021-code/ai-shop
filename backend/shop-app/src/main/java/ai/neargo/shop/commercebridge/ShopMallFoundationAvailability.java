package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.commerce.api.foundation.*;
import ai.neargo.commerce.api.mall.StorefrontFacade;
import ai.neargo.shop.auth.BizContext;

import java.time.Clock;
import java.util.*;

/** Legacy raw identity remains unchanged; the new mall verifies a separate typed current buyer. */
final class ShopMallFoundationAvailability implements FoundationPurchaseAvailabilityPort {
    private final FoundationAuthorityPort foundation;
    private final ShopMallAuthority mall;
    private final StorefrontFacade storefront;

    ShopMallFoundationAvailability(
            FoundationAuthorityPort foundation,
            ShopMallAuthority mall,
            StorefrontFacade storefront) {
        this.foundation = foundation;
        this.mall = mall;
        this.storefront = storefront;
    }

    public Set<String> availableSkuVersions(
            ActorContext actor, FoundationQueryFacade.PlanCatalog catalog) {
        String store = BizContext.current().currentStoreNo();
        if (store == null || store.isBlank()) return Set.of();
        var now = Clock.systemUTC().instant();
        if (!foundation.account(actor, now).isAfter(now)) return Set.of();
        var buyer = mall.buyer(store, "commerce:mall:purchase");
        var live = storefront.catalog(buyer.actor(), buyer.store());
        if (!live.canProcure() || !catalog.businessTypeCode().equals(live.businessTypeCode()))
            return Set.of();
        var requested = new HashMap<String, FoundationQueryFacade.PriceOption>();
        catalog.plans()
                .forEach(p -> p.priceOptions().forEach(s -> requested.put(s.skuVersionNo(), s)));
        var available = new HashSet<String>();
        live.products()
                .forEach(
                        p ->
                                p.skus()
                                        .forEach(
                                                s -> {
                                                    var price =
                                                            requested.get(s.sku().skuVersionNo());
                                                    if (price != null
                                                            && s.readiness().available()
                                                            && price.skuNo().equals(s.sku().skuNo())
                                                            && price.price()
                                                                    .equals(s.sku().price()))
                                                        available.add(s.sku().skuVersionNo());
                                                }));
        return Set.copyOf(available);
    }
}
