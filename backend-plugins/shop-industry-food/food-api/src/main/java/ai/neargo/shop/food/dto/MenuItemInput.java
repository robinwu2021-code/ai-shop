package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Restaurant-owned definition references original product and SKU identities without prices or
 * stock.
 */
public record MenuItemInput(
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo,
        @JsonDeserialize(using = MenuDefinitionPolicy.IntegerDeserializer.class) Integer sortNo,
        @JsonDeserialize(using = MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuScope,
        @JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class)
                List<String> skuNos,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String optionMode,
        @JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class)
                List<String> optionGroupNos,
        List<MenuSkuRecipeInput> skuRecipes,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<MenuSkuPriceInput> skuPrices) {
    public MenuItemInput(
            String item,
            String goods,
            Integer sort,
            Boolean enabled,
            String scope,
            List<String> skus,
            String mode,
            List<String> groups,
            List<MenuSkuRecipeInput> recipes) {
        this(item, goods, sort, enabled, scope, skus, mode, groups, recipes, null);
    }

    public MenuItemInput {
        if (skuPrices != null) skuPrices = List.copyOf(skuPrices);
    }

    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown composition field");
    }
}
