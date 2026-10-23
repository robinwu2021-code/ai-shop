package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Restaurant credentials never accept caller-owned scope. */
public record RotateTableTokenReq(@NotNull Instant expiresAt, @NotNull @Min(0) Long version) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown dining session parameter");
    }
    @Override public String toString() { return "RotateTableTokenReq[REDACTED]"; }
}
