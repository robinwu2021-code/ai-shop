package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** The menu item is the selection identity; equal SKU numbers in different menu items remain separate. */
public record CatalogItemVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuItemNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String categoryNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String categoryName,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String goodsNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String title,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cover,
        DishVO dish,
        @JsonDeserialize(contentUsing=MaterialCatalogFacts.TextDeserializer.class) List<String> optionGroupNos,
        List<CatalogSkuVO> skus) {
    public CatalogItemVO {
        if (optionGroupNos != null) optionGroupNos = List.copyOf(optionGroupNos);
        if (skus != null) skus = List.copyOf(skus);
    }
}
