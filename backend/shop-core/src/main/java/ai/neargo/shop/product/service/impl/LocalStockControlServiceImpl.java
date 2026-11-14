package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.product.policy.LocalStockStrategyPolicy;
import ai.neargo.shop.product.service.LocalStockControlService;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.spi.product.LocalStockControlPort.*;
import ai.neargo.shop.spi.product.LocalStockFactsPort;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Explicit read-only routing; legacy order authorities and all mutation ports remain unchanged. */
@Service
public class LocalStockControlServiceImpl implements LocalStockControlService {
    private final Map<String, List<LocalStockFactsPort>> owners;
    private final StockReplenishmentAdmissionService replenishments;
    public LocalStockControlServiceImpl(List<LocalStockFactsPort> providers,
            StockReplenishmentAdmissionService replenishments) {
        owners = providers.stream().collect(Collectors.groupingBy(LocalStockFactsPort::owner));
        this.replenishments = replenishments;
    }
    @Override public Preflight preflight(Scope scope, Request request) {
        var selection = LocalStockStrategyPolicy.select(scope, request);
        if (!selection.issues().isEmpty()) return result(null, null, Set.of(), selection.issues());
        boolean replenish = selection.route().strategy() == Strategy.REPLENISH;
        // Published only where this store really admitted replenishment; the switch itself is not the capability.
        if (replenish && !replenishments.connection(scope).enabled())
            return result(selection.route(), null, Set.of(), List.of(Issue.STRATEGY_NOT_ENABLED));
        var candidates = owners.getOrDefault(selection.route().owner(), List.of());
        if (candidates.size() != 1) return result(selection.route(), null, Set.of(), List.of(
                candidates.isEmpty() ? Issue.EXECUTOR_UNAVAILABLE : Issue.EXECUTOR_AMBIGUOUS));
        var facts = candidates.getFirst().inspect(scope, request);
        var capabilities = new LinkedHashSet<>(facts.capabilities());
        if (replenish) capabilities.addAll(Set.of(Action.RECEIVE, Action.ADJUST));
        if (!facts.issues().isEmpty()) return result(selection.route(), facts.stock(), capabilities, facts.issues());
        var issues = new LinkedHashSet<Issue>();
        if (request.quantity().stripTrailingZeros().scale() > facts.maxFractionDigits())
            issues.add(Issue.QUANTITY_PRECISION_UNSUPPORTED);
        if (!capabilities.containsAll(request.actions())) issues.add(Issue.CAPABILITY_UNSUPPORTED);
        var stock = facts.stock();
        if (stock.status() == StockStatus.UNTRACKED && !request.actions().equals(Set.of(Action.QUERY)))
            issues.add(Issue.NOT_TRACKED);
        if (request.actions().contains(Action.RESERVE) && stock.availableQuantity() != null
                && request.quantity().compareTo(stock.availableQuantity()) > 0)
            issues.add(Issue.INSUFFICIENT_STOCK);
        return result(selection.route(), stock, capabilities, new ArrayList<>(issues));
    }
    private static Preflight result(Route route, Observation stock, Set<Action> capabilities, List<Issue> issues) {
        return new Preflight(route, stock, capabilities, issues, issues.isEmpty());
    }
}
