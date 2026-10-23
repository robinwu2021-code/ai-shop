package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Restaurant credentials never accept caller-owned scope. */
public record StartDiningSessionReq(@NotBlank @Size(max=128) String tableToken, @Size(max=512) String deviceCredential) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown dining session parameter");
    }
    @Override public String toString() { return "StartDiningSessionReq[REDACTED]"; }
}
