package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CatalogDependencyVO;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Display receipts preserve selection identity and cannot turn unknown component authorities into sale permission. */
public final class CatalogPolicy {
    private CatalogPolicy() { }
    public static void query(CatalogQuery q) {
        if (q == null || !Set.of("POS", "WAITER", "KIOSK", "QR", "H5", "APP")
                .contains(q.orderSource() == null ? "" : q.orderSource())
                || !Set.of("DINE_IN", "PICKUP", "DELIVERY").contains(q.fulfillment() == null ? "" : q.fulfillment())
                || q.channelBindingNo() != null) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    public static void publicScope(String fulfillment) {
        if (!"PICKUP".equals(fulfillment) && !"DELIVERY".equals(fulfillment)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    public static void receipt(CatalogVO v, String store, CatalogQuery q) {
        if (v == null || !store.equals(v.storeNo()) || !q.orderSource().equals(v.orderSource())
                || !q.fulfillment().equals(v.fulfillment()) || v.asOf() == null || v.menus() == null
                || v.items() == null) invalid();
        var menus = new HashMap<String, Set<String>>();
        var dependencies = new HashMap<String, Map<String, CatalogDependencyVO>>();
        for (var m : v.menus()) {
            if (m == null || !key(m.menuNo()) || !key(m.revisionNo()) || m.revision() == null || m.revision() < 1
                    || m.name() == null || m.name().isBlank() || m.priority() == null || m.dependencies() == null
                    || menus.put(m.menuNo(), new HashSet<>()) != null) invalid();
            var deps = new HashMap<String, CatalogDependencyVO>();
            for (var d : m.dependencies()) {
                if (d == null || !key(d.revisionNo()) || !key(d.configNo()) || d.revision() == null || d.revision() < 1
                        || d.contentHash() == null || !d.contentHash().matches("[0-9a-f]{64}") || d.configKind() == null
                        || !Set.of("OPTION_GROUP", "MODIFIER", "BUNDLE", "RECIPE").contains(d.configKind())
                        || deps.put(d.configKind() + ":" + d.configNo(), d) != null) invalid();
                int count = (d.optionGroup() == null ? 0 : 1) + (d.modifier() == null ? 0 : 1)
                        + (d.bundle() == null ? 0 : 1) + (d.recipe() == null ? 0 : 1);
                if (count != 1 || switch (d.configKind()) {
                    case "OPTION_GROUP" -> d.optionGroup() == null || !d.configNo().equals(d.optionGroup().configNo());
                    case "MODIFIER" -> d.modifier() == null || !d.configNo().equals(d.modifier().configNo());
                    case "BUNDLE" -> d.bundle() == null || !d.configNo().equals(d.bundle().configNo());
                    case "RECIPE" -> d.recipe() == null || !d.configNo().equals(d.recipe().recipeNo())
                            || !store.equals(d.recipe().storeNo());
                    default -> true;
                }) invalid();
                if (d.specValues() == null) invalid();
                var values = new HashSet<String>();
                for (var value : d.specValues()) {
                    if (value == null || !MaterialCatalogFacts.validKey(value.valueNo(), 64)
                            || !MaterialCatalogFacts.validKey(value.dimNo(), 64) || !values.add(value.valueNo())
                            || value.label() == null || value.label().isBlank() || value.dimName() == null
                            || value.valueType() == null || value.usageType() == null || value.valueVersion() == null
                            || value.valueVersion() < 0 || value.dimVersion() == null || value.dimVersion() <
                                    0) invalid();
                }
                if (d.optionGroup() != null) for (var option : d.optionGroup().data().items()) {
                    if ("SPEC_VALUE".equals(option.sourceKind()) && !values.contains(option.specValueNo())) invalid();
                }
                if (d.recipe() != null && (!key(d.recipe().skuNo()) || d.recipe().version() == null
                        || d.recipe().version() < 0 || d.recipe().configVersion() == null
                        || d.recipe().configVersion() < 0)) invalid();
                if (d.modifier() != null) {
                    var modifier = d.modifier();
                    if (modifier.version() == null || modifier.version() < 0 || modifier.name() == null
                            || modifier.name().isBlank() || modifier.kind() == null) invalid();
                    if (modifier.salesSkuNo() == null) {
                        if (modifier.salesOffer() != null) invalid();
                    } else {
                        if (!key(modifier.salesSkuNo()) || modifier.salesOffer() == null
                                || !modifier.salesSkuNo().equals(modifier.salesOffer().skuNo())) invalid();
                        sku(modifier.salesOffer(), true,
                                "ADDON".equals(modifier.kind()) ? null : "COMPONENT_QUOTE_REQUIRED");
                    }
                }
            }
            dependencies.put(m.menuNo(), deps);
        }
        for (var item : v.items()) {
            if (item == null || !key(item.menuNo()) || !key(item.menuItemNo()) || !key(item.categoryNo())
                    || !key(item.goodsNo()) || !menus.containsKey(item.menuNo())
                    || !menus.get(item.menuNo()).add(item.menuItemNo()) || item.categoryName() == null
                    || item.title() == null || item.dish() == null || !item.goodsNo().equals(item.dish().goodsNo())
                    || item.dish().presentation() == null || item.dish().measurement() == null
                    || item.optionGroupNos() == null || item.skus() == null || item.skus().isEmpty()) invalid();
            var deps = dependencies.get(item.menuNo()); var groups = new HashSet<String>();
            for (String group : item.optionGroupNos()) if (!key(group) || !groups.add(group)
                    || !deps.containsKey("OPTION_GROUP:" + group)) invalid();
            var skus = new HashSet<String>();
            for (var s : item.skus()) {
                if (s == null || !skus.add(s.skuNo())) invalid();
                if (s.recipeNo() != null) {
                    var recipe = deps.get("RECIPE:" + s.recipeNo());
                    if (!key(s.recipeNo()) || recipe == null || recipe.recipe() == null
                            || !s.skuNo().equals(recipe.recipe().skuNo())) invalid();
                }
                String quote = "BUNDLE".equals(item.dish().dishKind()) ? "BUNDLE_QUOTE_REQUIRED"
                        : s.recipeNo() != null ? "RECIPE_STOCK_UNVERIFIED"
                                : !item.optionGroupNos().isEmpty() ? "SELECTION_QUOTE_REQUIRED" : null;
                sku(s, "COUNT".equals(item.dish().measurement().mode()), quote);
            }
        }
    }
    private static void sku(CatalogSkuVO s, boolean count, String quoteReason) {
        if (s == null || !key(s.skuNo()) || s.onSale() == null || s.restricted() == null || s.transactable() == null
                || s.pricingStatus() == null || s.stockStatus() == null || s.requiresSelectionQuote() == null
                || s.requiresSelectionQuote() != (quoteReason != null)
                || s.remainingQuota() != null && s.remainingQuota().signum() < 0) invalid();
        boolean price = "KNOWN".equals(s.pricingStatus()); boolean stock = "KNOWN".equals(s.stockStatus());
        if (price ? s.priceMinor() == null || s.priceMinor() < 0 || !"CNY".equals(s.currency())
                : !List.of("UNKNOWN", "UNAVAILABLE").contains(s.pricingStatus())
                        || s.priceMinor() != null || s.currency() != null) invalid();
        if (stock ? s.available() == null || s.available() < 0
                : !List.of("UNKNOWN", "UNAVAILABLE").contains(s.stockStatus()) || s.available() != null) invalid();
        boolean transact = s.onSale() && count && !s.restricted() && !s.requiresSelectionQuote() && price && stock
                && s.available() > 0 && (s.remainingQuota() == null ||
                        s.remainingQuota().compareTo(BigDecimal.ONE) >= 0);
        if (s.transactable() != transact || transact && s.reason() != null
                || !transact && (s.reason() == null || s.reason().isBlank())
                || s.onSale() && count && !s.restricted() && quoteReason != null &&
                        !quoteReason.equals(s.reason())) invalid();
    }
    private static boolean key(String value) { return MaterialCatalogFacts.validKey(value, 32); }
    private static void invalid() { throw new IllegalStateException("Invalid published catalog receipt"); }
}
