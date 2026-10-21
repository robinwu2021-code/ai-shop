package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import tools.jackson.databind.annotation.JsonDeserialize;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

/** Editable configuration input; financial and ownership facts remain server-controlled. */
public record ReplaceBundleDefinitionReq(
        @NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @NotNull @Size(max=30) List<@NotNull @Valid BundleGroupInput> groups,
        @NotNull @Size(max=100) List<@NotNull @Valid BundleChoiceInput> choices) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown bundle field");
    }
}
