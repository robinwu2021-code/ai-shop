package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.policy.CatalogCurrencySession;
import ai.neargo.shop.food.service.CatalogCurrencyService;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.time.Clock;
import java.util.function.Supplier;

/** Default-off selection never invents a market, substitutes CN for a foreign market, or queries tax/price. */
public final class CatalogCurrencyServiceImpl implements CatalogCurrencyService {
    private final GoodsCatalogQueryPort goods;
    private final Supplier<CatalogPriceSourcePort> markets;
    private final boolean enabled;
    private final Clock clock;

    /** Lazy owner resolution keeps disabled deployments independent of a new market-provider bean. */
    public CatalogCurrencyServiceImpl(GoodsCatalogQueryPort goods, Supplier<CatalogPriceSourcePort> markets,
            boolean enabled, Clock clock) {
        this.goods = goods;
        this.markets = markets;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Override
    public CatalogCurrencySession open(String entity, String store) {
        if (!enabled) return CatalogCurrencySession.legacy(goods, entity, store);
        var owner = markets.get();
        if (owner == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return CatalogCurrencySession.capture(goods, owner, entity, store, clock);
    }
}
