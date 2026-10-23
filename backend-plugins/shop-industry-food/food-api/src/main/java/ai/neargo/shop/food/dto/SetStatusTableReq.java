package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Table configuration never accepts caller-owned identity or occupancy state. */
public record SetStatusTableReq(
        @NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status,
        @NotNull @Min(0) Long version,
        @NotBlank @Size(max=255) String reason) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown table parameter");
    }
}
