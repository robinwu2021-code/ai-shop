package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Owner, identity and pricing are controlled by their original authorities. */
public record CreateModifierReq(@NotBlank @Size(max=64) String name,
        @NotBlank @Size(max=16) String kind, @Size(max=32) String salesSkuNo,
        @NotNull @Size(max=100) List<@NotNull @Valid ModifierComponentInput> components) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown modifier field");
    }
}
