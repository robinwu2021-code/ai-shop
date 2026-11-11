package ai.neargo.shop.product.port;

import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.product.LocalStockControlPort.*;
import ai.neargo.shop.spi.product.LocalStockFactsPort;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Reads the actual FOOD_LOCAL contract, including its remote deployment, without native inventory fallback. */
@Component
public class FoodLocalStockFactsPortImpl implements LocalStockFactsPort {
    private static final Set<Action> ACTIONS = Set.of(Action.QUERY, Action.RESERVE, Action.ACCEPT,
            Action.ISSUE_HANDOVER, Action.ISSUE_PRODUCTION, Action.RELEASE, Action.RETURN);
    private final ObjectProvider<FoodLocalStockPort> providers;
    public FoodLocalStockFactsPortImpl(ObjectProvider<FoodLocalStockPort> providers) { this.providers = providers; }
    @Override public String owner() { return "FOOD_LOCAL"; }
    @Override public Facts inspect(Scope scope, Request request) {
        var candidates = providers.orderedStream().limit(2).toList();
        if (candidates.size() != 1) return Facts.unavailable(candidates.isEmpty()
                ? Issue.EXECUTOR_UNAVAILABLE : Issue.EXECUTOR_AMBIGUOUS);
        var observed = candidates.getFirst().findAvailability(new FoodLocalStockPort.Scope(scope.entityNo(), scope.storeNo()),
                List.of(new FoodLocalStockPort.ItemRef(request.itemNo(), request.baseUnit())));
        if (observed.size() != 1) return Facts.unavailable(Issue.ITEM_NOT_READY);
        var item = observed.getFirst();
        if (!request.itemNo().equals(item.itemNo()) || !request.baseUnit().equals(item.baseUnit()))
            return Facts.unavailable(Issue.ITEM_NOT_READY);
        StockStatus status = switch (item.status()) {
            case "TRACKED" -> StockStatus.TRACKED;
            case "UNTRACKED" -> StockStatus.UNTRACKED;
            case "DISABLED" -> StockStatus.DISABLED;
            case "UNCONFIGURED" -> StockStatus.UNCONFIGURED;
            default -> StockStatus.UNKNOWN;
        };
        var stock = new Observation(item.itemNo(), item.baseUnit(), QuantityScope.STORE, scope.storeNo(),
                status, status == StockStatus.TRACKED ? item.availableQty() : null);
        boolean ready = status == StockStatus.UNTRACKED || (status == StockStatus.TRACKED
                && item.availableQty() != null && item.availableQty().signum() >= 0);
        return new Facts(stock, 6, ACTIONS, ready ? List.of() : List.of(Issue.ITEM_NOT_READY));
    }
}
