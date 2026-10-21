package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Explicit draft quantities never infer the inventory effect of a display name. */
public record RecipeAdjustmentInput(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String adjustmentNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionItemNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String targetLineNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String action,
        BigDecimal qty,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown recipe adjustment field");
    }
}
