package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuSkuRecipeInput;
import ai.neargo.shop.food.dto.ReplaceMenuCompositionReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Bounded, canonical restaurant structure; authority and existing-ID checks are performed by the
 * owning service.
 */
public final class MenuCompositionDefinitions {
    private MenuCompositionDefinitions() {}

    public static MenuCompositionVO normalize(ReplaceMenuCompositionReq req) {
        return normalizeForCurrency(req, "CNY");
    }

    /** Live write context chooses currency before accepting caller price labels. */
    public static MenuCompositionVO normalizeForCurrency(ReplaceMenuCompositionReq req, String currency) {
        require(
                req != null
                        && "COMPOSITION".equals(req.section())
                        && req.version() != null
                        && req.version() >= 0
                        && req.version() < Long.MAX_VALUE);
        return normalizeForCurrency(new MenuCompositionVO(req.categories()), currency);
    }

    public static MenuCompositionVO normalize(MenuCompositionVO definition) {
        return normalizeForCurrency(definition, "CNY");
    }

    /** Structural parsing alone grants no authority to maintain or publish a foreign-currency menu. */
    public static MenuCompositionVO normalizeStructure(MenuCompositionVO definition) {
        return normalizeDefinition(definition, null);
    }

    /** Frozen or live server context supplies the expected currency; caller labels cannot select it. */
    public static MenuCompositionVO normalizeForCurrency(MenuCompositionVO definition, String currency) {
        MenuPricePolicy.normalizeForCurrency(null, currency);
        return normalizeDefinition(definition, currency);
    }

    private static MenuCompositionVO normalizeDefinition(MenuCompositionVO definition, String currency) {
        require(
                definition != null
                        && definition.categories() != null
                        && definition.categories().size() <= 30);
        var categoryIds = new HashSet<String>();
        var itemIds = new HashSet<String>();
        var categorySorts = new HashSet<Integer>();
        var categories = new ArrayList<MenuCategoryInput>();
        int count = 0;
        for (var category : definition.categories()) {
            require(
                    category != null
                            && optionalKey(category.categoryNo())
                            && text(category.name(), 64)
                            && order(category.sortNo())
                            && categorySorts.add(category.sortNo())
                            && category.items() != null);
            require(category.categoryNo() == null || categoryIds.add(category.categoryNo()));
            var goods = new HashSet<String>();
            var itemSorts = new HashSet<Integer>();
            var items = new ArrayList<MenuItemInput>();
            for (var item : category.items()) {
                require(
                        ++count <= 300
                                && item != null
                                && optionalKey(item.menuItemNo())
                                && MaterialCatalogFacts.validKey(item.goodsNo(), 32)
                                && goods.add(item.goodsNo())
                                && order(item.sortNo())
                                && itemSorts.add(item.sortNo())
                                && item.enabled() != null
                                && item.skuNos() != null
                                && item.skuNos().size() <= 100
                                && item.optionGroupNos() != null
                                && item.optionGroupNos().size() <= 50
                                && item.skuRecipes() != null
                                && item.skuRecipes().size() <= 100);
                require(item.menuItemNo() == null || itemIds.add(item.menuItemNo()));
                require(
                        List.of("ALL", "EXPLICIT")
                                        .contains(item.skuScope() == null ? "" : item.skuScope())
                                && ("ALL".equals(item.skuScope())
                                        ? item.skuNos().isEmpty()
                                        : !item.skuNos().isEmpty()));
                require(
                        List.of("INHERIT", "REPLACE")
                                        .contains(
                                                item.optionMode() == null ? "" : item.optionMode())
                                && (!"INHERIT".equals(item.optionMode())
                                        || item.optionGroupNos().isEmpty()));
                keys(item.skuNos());
                keys(item.optionGroupNos());
                var recipes = new HashSet<String>();
                for (var recipe : item.skuRecipes()) {
                    require(
                            recipe != null
                                    && "EXPLICIT".equals(item.skuScope())
                                    && item.skuNos().contains(recipe.skuNo())
                                    && recipes.add(recipe.skuNo())
                                    && MaterialCatalogFacts.validKey(recipe.recipeNo(), 32));
                }
                items.add(
                        new MenuItemInput(
                                item.menuItemNo(),
                                item.goodsNo(),
                                item.sortNo(),
                                item.enabled(),
                                item.skuScope(),
                                item.skuNos().stream().sorted().toList(),
                                item.optionMode(),
                                List.copyOf(item.optionGroupNos()),
                                item.skuRecipes().stream()
                                        .sorted(Comparator.comparing(MenuSkuRecipeInput::skuNo))
                                        .toList(),
                                currency == null ? MenuPricePolicy.normalizeStructure(item.skuPrices())
                                        : MenuPricePolicy.normalizeForCurrency(item.skuPrices(), currency)));
            }
            categories.add(
                    new MenuCategoryInput(
                            category.categoryNo(),
                            category.name(),
                            category.sortNo(),
                            items.stream()
                                    .sorted(Comparator.comparing(MenuItemInput::sortNo))
                                    .toList()));
        }
        return new MenuCompositionVO(
                categories.stream()
                        .sorted(Comparator.comparing(MenuCategoryInput::sortNo))
                        .toList());
    }

    private static void keys(List<String> keys) {
        require(
                keys.stream().allMatch(k -> MaterialCatalogFacts.validKey(k, 32))
                        && new HashSet<>(keys).size() == keys.size());
    }

    private static boolean optionalKey(String key) {
        return key == null || MaterialCatalogFacts.validKey(key, 32);
    }

    private static boolean text(String value, int length) {
        return value != null
                && !value.isBlank()
                && value.length() <= length
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean order(Integer n) {
        return n != null && n >= 0;
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
