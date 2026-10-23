package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** Stable business references allow selection rules to refer to new children in the same request. */
public record OptionItemInput(@NotBlank @Size(max=32) String optionItemNo,
        @NotBlank String sourceKind, @Size(max=64) String specValueNo, @Size(max=32) String modifierNo,
        @NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal minQty,
        @NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal maxQty,
        @NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal defaultQty,
        @NotNull @Min(0) Integer sortNo) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown option field"); }
}
