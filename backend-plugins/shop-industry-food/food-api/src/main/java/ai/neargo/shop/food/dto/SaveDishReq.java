package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A missing version creates an extension; updates require the exact existing version. */
public record SaveDishReq(
        @NotBlank @Pattern(regexp="SINGLE|BUNDLE|ADDON") String dishKind,
        @NotNull @Min(0) Integer prepSeconds,
        @Valid @NotNull DishPresentation presentation,
        @Valid @NotNull MeasurementPolicy measurement,
        @Min(0) Long version) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown dish field"); }
}
