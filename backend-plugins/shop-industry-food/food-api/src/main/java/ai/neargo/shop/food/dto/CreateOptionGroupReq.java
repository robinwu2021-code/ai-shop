package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Accepts only restaurant selection constraints; identity, owner and publication are server controlled. */
public record CreateOptionGroupReq(@NotBlank @Size(max=64) String name,
        @NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal minQty,
        @NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal maxQty) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown group field"); }
}
