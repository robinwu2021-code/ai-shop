package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import jakarta.validation.constraints.Min;

/** Editable configuration input; financial and ownership facts remain server-controlled. */
public record BundleGroupInput(@NotBlank @Size(max=32) String groupNo, @NotBlank @Size(max=64) String name,
        @NotNull BigDecimal minQty, @NotNull BigDecimal maxQty, @Size(max=32) String baseSkuNo,
        @NotNull @Min(0) Integer sortNo) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown bundle field");
    }
}
