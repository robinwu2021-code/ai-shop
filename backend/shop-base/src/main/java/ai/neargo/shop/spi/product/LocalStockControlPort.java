package ai.neargo.shop.spi.product;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Read-only local capability observation; a result is neither a reservation nor a published binding. */
public interface LocalStockControlPort {
    enum Industry { RETAIL, FOOD, BEAUTY }
    enum Strategy { LOCAL, REPLENISH, SHARED_SYNC, DELEGATED }
    enum ItemKind { SKU, MATERIAL, CONSUMABLE, SERVICE_RIGHT, RESOURCE_CAPACITY }
    enum Action { QUERY, RESERVE, ACCEPT, ISSUE_HANDOVER, ISSUE_PRODUCTION, ISSUE_SERVICE_USE,
        RELEASE, RETURN, RECEIVE, ADJUST }
    enum QuantityScope { ENTITY, STORE }
    enum StockStatus { TRACKED, UNTRACKED, UNCONFIGURED, DISABLED, UNKNOWN }
    enum Issue { STRATEGY_NOT_ENABLED, NOT_PHYSICAL_STOCK, ITEM_KIND_UNSUPPORTED,
        SCOPE_UNSUPPORTED, EXECUTOR_UNAVAILABLE, EXECUTOR_AMBIGUOUS, ITEM_NOT_FOUND,
        ITEM_NOT_READY, UNIT_UNCONFIGURED, UNIT_MISMATCH, AMBIGUOUS_STOCK,
        QUANTITY_PRECISION_UNSUPPORTED, CAPABILITY_UNSUPPORTED, NOT_TRACKED, INSUFFICIENT_STOCK }

    /** Supplied by an authenticated business entry or a trusted original owner, never by public JSON. */
    record Scope(String tenantNo, String entityNo, String storeNo) { }

    /** Industry describes the requested diagnostic profile, not a new authority for an existing order. */
    record Request(@NotNull Industry industry, @NotNull Strategy strategy, @NotNull ItemKind itemKind,
                   @NotBlank @Size(max = 32) String itemNo, @NotBlank @Size(max = 16) String baseUnit,
                   @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6)
                   BigDecimal quantity, @NotEmpty Set<@NotNull Action> actions) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown local stock preflight field");
        }
    }

    /** A diagnostic executor description, not a client-supplied permission or a migrated original. */
    record Route(Industry industry, Strategy strategy, String owner) { }

    /** Only observed quantities are returned. Unknown/untracked is null, not zero or unlimited. */
    record Observation(String itemNo, String baseUnit, QuantityScope quantityScope, String scopeRef,
                       StockStatus status, BigDecimal availableQuantity) { }

    record Preflight(Route route, Observation stock, Set<Action> capabilities, List<Issue> issues,
                     boolean allowed) {
        public Preflight { capabilities = Set.copyOf(capabilities); issues = List.copyOf(issues); }
    }

    Preflight preflight(Scope scope, Request request);
}
