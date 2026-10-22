package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** Restaurant-owned definition references original product and SKU identities without prices or stock. */
public record MenuCategoryInput(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String categoryNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer sortNo,
        List<MenuItemInput> items) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown composition field");
    }
}
