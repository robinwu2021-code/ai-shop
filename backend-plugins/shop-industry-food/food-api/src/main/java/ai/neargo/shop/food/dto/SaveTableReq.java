package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Table configuration never accepts caller-owned identity or occupancy state. */
public record SaveTableReq(
        @NotBlank @Size(max=32) String areaNo,
        @NotBlank @Size(max=64) String name,
        @NotNull @Min(1) Integer capacity,
        @NotNull Integer sortNo,
        @NotNull @Min(0) Long version) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown table parameter");
    }
}
