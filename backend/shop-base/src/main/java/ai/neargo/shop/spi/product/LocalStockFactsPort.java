package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.LocalStockControlPort.*;
import java.util.List;
import java.util.Set;

/** Local business owners expose facts without installing an inventory service or a second balance. */
public interface LocalStockFactsPort {
    record Facts(Observation stock, int maxFractionDigits, Set<Action> capabilities, List<Issue> issues) {
        public Facts { capabilities = Set.copyOf(capabilities); issues = List.copyOf(issues); }
        public static Facts unavailable(Issue issue) { return new Facts(null, 0, Set.of(), List.of(issue)); }
    }
    String owner();
    Facts inspect(Scope scope, Request request);
}
