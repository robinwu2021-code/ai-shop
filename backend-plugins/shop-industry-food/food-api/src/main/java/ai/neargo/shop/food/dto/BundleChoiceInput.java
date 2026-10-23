package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import jakarta.validation.constraints.Min;

/** Editable configuration input; financial and ownership facts remain server-controlled. */
public record BundleChoiceInput(@NotBlank @Size(max=32) String choiceNo, @NotBlank @Size(max=32) String groupNo,
        @NotBlank @Size(max=32) String skuNo, @NotNull BigDecimal unitsPerPick,
        @NotBlank @Size(max=24) String priceMode, @NotNull BigDecimal defaultQty,
        @NotNull BigDecimal minQty, @NotNull BigDecimal maxQty, @NotNull @Min(0) Integer sortNo) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown bundle field");
    }
}
