package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.food.dto.MenuSkuPriceInput;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Published menu prices are scoped to one item/selection tree; missing overrides retain native SKU
 * pricing.
 */
public final class MenuPricePolicy {
    private MenuPricePolicy() {}

    public static List<MenuSkuPriceInput> normalize(List<MenuSkuPriceInput> prices) {
        if (prices == null || prices.isEmpty()) return null;
        if (prices.size() > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        var keys = new HashSet<String>();
        for (var price : prices) {
            if (price == null
                    || price.priceMinor() == null
                    || !"CNY".equals(price.currency())
                    || !keys.add(price.skuNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
            CartInputPolicy.key(price.skuNo());
            new MoneyMinor(price.currency(), price.priceMinor())
                    .requireNonNegative()
                    .requireJsonSafe();
        }
        return prices.stream().sorted(Comparator.comparing(MenuSkuPriceInput::skuNo)).toList();
    }

    public static MoneyMinor price(
            List<MenuSkuPriceInput> prices, String sku, MoneyMinor nativePrice) {
        var normalized = normalize(prices);
        if (normalized == null) return nativePrice;
        return normalized.stream()
                .filter(price -> sku.equals(price.skuNo()))
                .map(price -> new MoneyMinor(price.currency(), price.priceMinor()))
                .findFirst()
                .orElse(nativePrice);
    }
}
