package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Complete immutable policy publication; version compares against the latest published, including future policies. */
public record CreatePolicyReq(@NotBlank @Size(max = 64) String timezone,
        @NotNull @Min(0) @Max(1439) Integer cutoverMinute,
        @NotNull Instant effectiveFrom,
        @NotBlank @Pattern(regexp = "PREPAY|POSTPAY") String payPolicy,
        @NotNull @Size(max = 64) List<@NotBlank @Size(max = 64) String> capabilities,
        @Size(max = 64) String approvalRef,
        @NotNull @Min(0) Long version) {
    /** Rejects identity injection and misspelled settings locally without changing the platform ObjectMapper. */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown policy parameter");
    }
}
