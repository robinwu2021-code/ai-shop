package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.LocalStockControlPort.QuantityScope;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import java.math.BigDecimal;
import java.util.List;

/** Target-pool diagnostics only. Neither an admission journal nor permission to mutate stock. */
public interface RetailReplenishmentTargetPort {
    enum Issue {
        STORE_SCOPE_MISMATCH, NOT_RETAIL, EXECUTOR_UNAVAILABLE, EXECUTOR_NOT_PLATFORM,
        ITEM_NOT_FOUND, AMBIGUOUS_STOCK, STOCK_OWNER_MISMATCH, NOT_PHYSICAL_STOCK,
        UNIT_MISMATCH, POOL_UNCONFIGURED, INVALID_BALANCE, INVALID_QUANTITY,
        LEGACY_WRITEBACK_ENABLED, TARGET_CHANGED
    }

    /** Source quantity and conversion factor remain exact decimals; only their product must be integer. */
    record Mapping(String skuNo, String baseUnit, BigDecimal sourceQuantity, BigDecimal factor) { }

    /** Business identity plus opaque accounting-row fingerprint, never a database primary key. */
    record Target(String entityNo, String storeNo, String skuNo, String market, String baseUnit,
                  QuantityScope quantityScope, String poolRef, String identity) { }

    record Inspection(Target target, Integer onHand, Integer reserved, Integer receivingQuantity,
                      List<Issue> issues) {
        public Inspection { issues = List.copyOf(issues); }
        public boolean ready() { return issues.isEmpty(); }
        public boolean writeEnabled() { return false; }
    }

    /** Current target rows locked within the observing transaction; no persistent admission is created. */
    Inspection inspect(Scope scope, Mapping mapping);

    /** Join an existing transaction, lock current accounting rows, compare the original target. */
    Inspection requireCurrent(Scope scope, Mapping mapping, Target original);
}
