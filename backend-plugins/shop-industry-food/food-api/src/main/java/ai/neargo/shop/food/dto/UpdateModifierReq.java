package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Explicit full draft replacement; null salesSkuNo intentionally clears an earlier reference. */
public record UpdateModifierReq(@NotBlank @Size(max=64) String name,
        @NotBlank @Size(max=16) String kind, @Size(max=32) String salesSkuNo,
        @NotNull @Size(max=100) List<@NotNull @Valid ModifierComponentInput> components,
        @NotNull @Min(0) Long version,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=16) String inventoryMode) {
    public UpdateModifierReq(String name, String kind, String salesSkuNo,
            List<ModifierComponentInput> components, Long version) {
        this(name, kind, salesSkuNo, components, version, null);
    }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown modifier field");
    }
}
