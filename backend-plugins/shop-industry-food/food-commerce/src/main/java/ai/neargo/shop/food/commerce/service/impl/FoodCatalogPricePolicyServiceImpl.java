package ai.neargo.shop.food.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts.Facts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts.Query;
import ai.neargo.shop.food.commerce.policy.FoodCatalogPricePolicy;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.commerce.service.FoodCatalogPricePolicyService;
import ai.neargo.shop.food.service.MenuCatalogPriceService;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;

/** Binding rereads surround original pricing; the bridge never computes a second price. */
public final class FoodCatalogPricePolicyServiceImpl implements FoodCatalogPricePolicyService {
    private final FoodCatalogBindingService bindings;
    private final MenuCatalogPriceService prices;
    private final ObjectMapper json;
    private final Clock clock;

    public FoodCatalogPricePolicyServiceImpl(
            FoodCatalogBindingService bindings, MenuCatalogPriceService prices, ObjectMapper json, Clock clock) {
        this.bindings = bindings;
        this.prices = prices;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Facts capture(Query query) {
        FoodCatalogPricePolicy.validateQuery(query);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("FOOD_PRICE_POLICY_OUTSIDE_TRANSACTION_REQUIRED");
        var start = clock.instant();
        var before = bindings.capture(query.binding());
        FoodCatalogPricePolicy.validateBinding(json, query.binding(), before, clock.instant());
        var originalPrices = prices.capture(FoodCatalogPricePolicy.priceQuery(query));
        var after = bindings.capture(query.binding());
        var now = clock.instant();
        FoodCatalogPricePolicy.validateBinding(json, query.binding(), before, now);
        FoodCatalogPricePolicy.validateBinding(json, query.binding(), after, now);
        if (!before.menuProof().publication().equals(after.menuProof().publication())
                || !sameAuthority(before.authority(), after.authority())) throw BizException.of(ErrorCode.CONFLICT);
        var until = earliest(start.plusSeconds(30), before.validUntil());
        until = earliest(until, after.validUntil());
        until = earliest(until, originalPrices.validUntil());
        var result = FoodCatalogPricePolicy.seal(json, new Facts(1, query, 1, FoodCatalogPricePolicy.MODE,
                after, originalPrices, now, until, null));
        FoodCatalogPricePolicy.validate(json, query, result, now);
        return result;
    }

    private static boolean sameAuthority(
            ChannelCatalogAuthorityContracts.Facts a, ChannelCatalogAuthorityContracts.Facts b) {
        return a.provider().equals(b.provider()) && a.profile().equals(b.profile()) && a.account().equals(b.account())
                && a.connection().equals(b.connection()) && a.grant().equals(b.grant());
    }

    private static Instant earliest(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
