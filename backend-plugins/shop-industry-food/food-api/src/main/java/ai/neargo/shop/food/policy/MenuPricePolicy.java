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

    /** Legacy local ordering keeps its original CNY contract. */
    public static List<MenuSkuPriceInput> normalize(List<MenuSkuPriceInput> prices) {
        return normalizeForCurrency(prices, "CNY");
    }

    /** Structure validation is pure and cannot infer a merchant market from caller-supplied currency. */
    public static List<MenuSkuPriceInput> normalizeStructure(List<MenuSkuPriceInput> prices) {
        if (prices == null || prices.isEmpty()) return null;
        if (prices.size() > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        var keys = new HashSet<String>();
        for (var price : prices) {
            if (price == null || price.priceMinor() == null || !keys.add(price.skuNo())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            CartInputPolicy.key(price.skuNo());
            new MoneyMinor(price.currency(), price.priceMinor()).requireNonNegative().requireJsonSafe();
        }
        return prices.stream().sorted(Comparator.comparing(MenuSkuPriceInput::skuNo)).toList();
    }

    /** The caller must supply currency from verified server context; this method performs no conversion. */
    public static List<MenuSkuPriceInput> normalizeForCurrency(List<MenuSkuPriceInput> prices, String currency) {
        new MoneyMinor(currency, 0);
        if (prices != null && prices.stream().anyMatch(price -> price != null && !currency.equals(price.currency()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return normalizeStructure(prices);
    }

    /** Local checkout cannot consume a foreign native price even when an override happens to match. */
    public static MoneyMinor price(List<MenuSkuPriceInput> prices, String sku, MoneyMinor nativePrice) {
        return priceForCurrency(prices, sku, nativePrice, "CNY");
    }

    /** Exact minor-unit selection neither changes currency labels nor scales amounts or performs FX. */
    public static MoneyMinor priceForCurrency(List<MenuSkuPriceInput> prices, String sku,
            MoneyMinor nativePrice, String currency) {
        var normalized = normalizeForCurrency(prices, currency);
        if (nativePrice == null || !currency.equals(nativePrice.currency())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        nativePrice.requireNonNegative().requireJsonSafe();
        if (normalized == null) return nativePrice;
        return normalized.stream().filter(price -> sku.equals(price.skuNo()))
                .map(price -> new MoneyMinor(price.currency(), price.priceMinor())).findFirst().orElse(nativePrice);
    }
}
