package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Facts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * Captures original owners outside a local transaction; facts are not stock or payment
 * authorization.
 */
public class MenuCatalogPriceServiceImpl implements MenuCatalogPriceService {
    private final ConfigRevisionService revisions;
    private final Supplier<CatalogPriceSourcePort> prices;
    private final ObjectMapper json;
    private final Clock clock;

    public MenuCatalogPriceServiceImpl(
            ConfigRevisionService revisions,
            Supplier<CatalogPriceSourcePort> prices,
            ObjectMapper json,
            Clock clock) {
        this.revisions = revisions;
        this.prices = prices;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Facts capture(Query query) {
        var sourceQuery = MenuCatalogPricePolicy.sourceQuery(query);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Menu source capture cannot join an existing transaction");
        var before = revisions.verifyMenuResource(sourceQuery);
        MenuSourcePolicy.validateProof(json, sourceQuery, before, OffsetDateTime.now(clock));
        var request = MenuCatalogPricePolicy.productRequest(json, query, before);
        var owner = prices.get();
        if (owner == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var original = owner.capture(request);
        CatalogPriceSourceFacts.validate(request, original, clock.instant());
        var after = revisions.verifyMenuResource(sourceQuery);
        MenuSourcePolicy.validateProof(json, sourceQuery, after, OffsetDateTime.now(clock));
        if (!before.publication().equals(after.publication()))
            throw BizException.of(ErrorCode.CONFLICT);
        return MenuCatalogPricePolicy.seal(json, query, after, original, clock.instant());
    }
}
