package ai.neargo.shop.product.policy;

import ai.neargo.shop.spi.product.LocalStockControlPort.*;
import java.util.List;

/** Select an explicit diagnostic executor; only RETAIL SKU REPLENISH is published, nothing degrades to local stock. */
public final class LocalStockStrategyPolicy {
    private LocalStockStrategyPolicy() { }
    public record Selection(Route route, List<Issue> issues) { }
    public static Selection select(Scope scope, Request request) {
        boolean replenish = request.strategy() == Strategy.REPLENISH
                && request.industry() == Industry.RETAIL && request.itemKind() == ItemKind.SKU;
        if (request.strategy() != Strategy.LOCAL && !replenish) return rejected(Issue.STRATEGY_NOT_ENABLED);
        if (!"MAIN".equals(scope.tenantNo())) return rejected(Issue.SCOPE_UNSUPPORTED);
        if (request.itemKind() == ItemKind.SERVICE_RIGHT || request.itemKind() == ItemKind.RESOURCE_CAPACITY)
            return rejected(Issue.NOT_PHYSICAL_STOCK);
        if (request.industry() != Industry.FOOD && request.itemKind() != ItemKind.SKU)
            return rejected(Issue.ITEM_KIND_UNSUPPORTED);
        String owner = request.industry() == Industry.FOOD ? "FOOD_LOCAL" : "PLATFORM";
        return new Selection(new Route(request.industry(), request.strategy(), owner), List.of());
    }
    private static Selection rejected(Issue issue) { return new Selection(null, List.of(issue)); }
}
