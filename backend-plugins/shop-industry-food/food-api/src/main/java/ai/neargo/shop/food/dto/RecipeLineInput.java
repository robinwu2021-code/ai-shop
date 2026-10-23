package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A draft never grants conversion or inventory execution authority. Unknown fields fail closed. */
public record RecipeLineInput(@Size(max=32)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String lineNo,
        @NotBlank @Size(max=32)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemRef,
        @NotNull BigDecimal qty,
        @NotBlank @Size(max=16)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit,
        @NotNull BigDecimal lossRate) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown recipe field"); }
}
