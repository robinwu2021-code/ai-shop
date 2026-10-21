package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** One explicit section replaces composition, never the existing menu calendar or sales context. */
public record ReplaceMenuCompositionReq(@NotNull
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section,
        @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @NotNull @Size(max=30) List<MenuCategoryInput> categories) implements MenuDefinitionCommand {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown composition field");
    }
}
