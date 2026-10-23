package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A draft never grants conversion or inventory execution authority. Unknown fields fail closed. */
public record CreateRecipeReq(@NotBlank @Size(max=32)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @NotBlank @Size(max=32)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeCode,
        @NotNull BigDecimal yieldQty,
        @NotBlank @Size(max=16)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown recipe field"); }
}
