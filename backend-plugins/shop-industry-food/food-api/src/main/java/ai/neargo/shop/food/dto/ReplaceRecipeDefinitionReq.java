package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A draft never grants conversion or inventory execution authority. Unknown fields fail closed. */
public record ReplaceRecipeDefinitionReq(@NotNull @Min(0)
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @NotNull BigDecimal yieldQty,
        @NotBlank @Size(max=16)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit,
        @NotNull @Size(max=100) List<@NotNull @Valid RecipeLineInput> lines,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max=100) List<@NotNull @Valid RecipeAdjustmentInput> adjustments) {
    public ReplaceRecipeDefinitionReq(Long version, BigDecimal yieldQty, String yieldUnit,
            List<RecipeLineInput> lines) {
        this(version, yieldQty, yieldUnit, lines, null);
    }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown recipe field"); }
}
