package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.ReplaceMenuContextsReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Strict disjoint local and channel scopes; policy references are verified separately by their authority. */
public final class MenuContextDefinitions {
    private MenuContextDefinitions() { }
    /** One vocabulary is shared by menu contexts, catalog reads and sales restrictions. */
    public static boolean supportedSource(String value) {
        return value != null && List.of("POS", "WAITER", "KIOSK", "QR", "H5", "APP", "CHANNEL").contains(value);
    }
    public static boolean supportedFulfillment(String value) {
        return value != null && List.of("DINE_IN", "PICKUP", "DELIVERY").contains(value);
    }
    public static List<MenuContextInput> normalize(ReplaceMenuContextsReq req) {
        require(req != null && "CONTEXT".equals(req.section()) && req.version() != null
                && req.version() >= 0 && req.version() < Long.MAX_VALUE);
        return normalize(req.contexts());
    }
    public static List<MenuContextInput> normalize(List<MenuContextInput> contexts) {
        require(contexts != null && contexts.size() <= 50);
        var scopes = new HashSet<String>();
        for (var input : contexts) {
            require(input != null && supportedSource(input.orderSource())
                    && supportedFulfillment(input.fulfillment()));
            if ("CHANNEL".equals(input.orderSource())) {
                require("DELIVERY".equals(input.fulfillment())
                        && MaterialCatalogFacts.validKey(input.channelBindingNo(), 32)
                        && MaterialCatalogFacts.validKey(input.pricePolicyRef(), 64));
            } else require(input.channelBindingNo() == null && input.pricePolicyRef() == null);
            require(scopes.add(input.orderSource() + ":" + input.fulfillment() + ":"
                    + (input.channelBindingNo() == null ? "LOCAL" : input.channelBindingNo())));
        }
        return contexts.stream().sorted(Comparator.comparing(MenuContextInput::orderSource)
                .thenComparing(MenuContextInput::fulfillment)
                .thenComparing(v -> v.channelBindingNo() == null ? "LOCAL" : v.channelBindingNo())).toList();
    }
    private static void require(boolean valid) { if (!valid) throw BizException.of(ErrorCode.BAD_REQUEST); }
}
