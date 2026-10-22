package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** A component is a count of addon servings, never a material-unit conversion or price. */
public record ModifierComponentInput(@NotBlank @Size(max=32) String memberNo,
        @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=9, fraction=3) BigDecimal quantity) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown component field");
    }
}
