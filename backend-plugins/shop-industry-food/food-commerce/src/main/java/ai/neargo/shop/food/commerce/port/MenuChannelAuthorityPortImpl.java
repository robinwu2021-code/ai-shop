package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.food.commerce.policy.FoodCatalogPricePolicy;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

/** Configuration can verify original authority without requiring a menu that is already published. */
public final class MenuChannelAuthorityPortImpl implements MenuChannelAuthorityPort {
    private final MenuChannelBindingService bindings;
    private final ChannelCatalogAuthorityService authorities;
    private final CatalogPriceSourcePort products;
    private final Clock clock;

    public MenuChannelAuthorityPortImpl(
            MenuChannelBindingService bindings, ChannelCatalogAuthorityService authorities,
            CatalogPriceSourcePort products, Clock clock) {
        this.bindings = bindings;
        this.authorities = authorities;
        this.products = products;
        this.clock = clock;
    }

    @Override
    public boolean authorized(String entity, String store, String provider, String account, String remoteStore) {
        outsideTransaction();
        var query = new IdentityQuery(entity, store, provider, account, remoteStore);
        ChannelCatalogAuthorityPolicy.validateIdentityQuery(query);
        ChannelCatalogAuthorityPolicy.validateResolved(query, authorities.resolve(query), clock.instant());
        return true;
    }

    @Override
    public boolean pricePolicyAuthorized(String entity, String store, String number, String policy) {
        outsideTransaction();
        if (!FoodCatalogPricePolicy.REF.equals(policy)) return false;
        if (!MaterialCatalogFacts.validKey(number, 32)) return false;
        var scope = new MarketScope(entity, store);
        CatalogPriceSourceFacts.validateMarketScope(scope);
        var before = bindings.getBinding(entity, store, number);
        if (before == null || !store.equals(before.storeNo()) || !number.equals(before.bindingNo())
                || !"ACTIVE".equals(before.status())) return false;
        var query = new IdentityQuery(entity, store, before.provider(),
                before.channelAccountRef(), before.externalStoreRef());
        var authority = authorities.resolve(query);
        ChannelCatalogAuthorityPolicy.validateResolved(query, authority, clock.instant());
        var market = products.market(scope);
        var after = bindings.getBinding(entity, store, number);
        var now = clock.instant();
        ChannelCatalogAuthorityPolicy.validateResolved(query, authority, now);
        CatalogPriceSourceFacts.validateMarketFacts(scope, market, now);
        return before.equals(after) && authority.profile().market().equals(market.market().market());
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("FOOD_CHANNEL_AUTHORITY_OUTSIDE_TRANSACTION_REQUIRED");
    }
}
