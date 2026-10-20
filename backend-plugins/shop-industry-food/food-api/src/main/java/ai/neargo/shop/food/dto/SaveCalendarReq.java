package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A business-day exception narrows ordering hours without editing the shared store schedule. */
public record SaveCalendarReq(@NotBlank @Pattern(regexp = "OPEN|CLOSED") String mode,
        @NotNull @Size(max = 32) List<@NotBlank String> intervals, @NotNull @Min(0) Long version) {
    /** Identity and unsupported settings cannot be supplied through this public request. */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown calendar field");
    }
}
