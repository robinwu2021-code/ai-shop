package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * HTTP format constraints live here; authority and frozen service requirements remain server-owned.
 */
public record AssignServiceTaskReq(
        @JsonProperty(required = true) @Min(0) @Max(Long.MAX_VALUE - 1) long expectedTaskRevision,
        @JsonProperty(required = true) @Min(1) long expectedBindingRevision,
        @JsonProperty(required = true) @Min(1) long expectedScopeRevision,
        @JsonProperty(required = true) @Min(1) long expectedSourceRevision,
        @JsonProperty(required = true) @NotNull Instant startAt,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100)
                List<@NotNull @Valid Selection> assignments) {
    private static final String UNPADDED =
            "[^\\p{javaWhitespace}](?:[\\s\\S]*[^\\p{javaWhitespace}])?";

    /**
     * Whole-millisecond transport precision is separate from owner qualification and time-window
     * rules.
     */
    @AssertTrue
    @JsonIgnore
    public boolean isStartAtMillisecondPrecision() {
        return startAt == null || startAt.getNano() % 1_000_000 == 0;
    }

    /**
     * The finite request names each requirement slot once; frozen-definition matching is a business
     * rule.
     */
    @AssertTrue
    @JsonIgnore
    public boolean isUniqueRequirementSlots() {
        if (assignments == null) return true;
        var slots = new HashSet<Slot>();
        for (var selection : assignments) {
            if (selection != null
                    && !slots.add(new Slot(selection.requirementNo(), selection.ordinal())))
                return false;
        }
        return true;
    }

    /**
     * Presence is required even when personnelNo is explicitly null for a physical resource
     * selection.
     */
    public record Selection(
            @JsonProperty(required = true) @NotBlank @Size(max = 32) @Pattern(regexp = UNPADDED)
                    String requirementNo,
            @JsonProperty(required = true) @Min(0) @Max(99) int ordinal,
            @JsonProperty(required = true) @NotBlank @Size(max = 32) @Pattern(regexp = UNPADDED)
                    String resourceNo,
            @JsonProperty(required = true) @Size(max = 32) @Pattern(regexp = UNPADDED)
                    String personnelNo) {}

    private record Slot(String requirementNo, int ordinal) {}
}
