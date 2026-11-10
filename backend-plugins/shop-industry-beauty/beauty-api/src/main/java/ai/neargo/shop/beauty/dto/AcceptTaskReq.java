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

import java.util.HashSet;
import java.util.List;

/**
 * Clients select frozen slots only; personnel identity and qualification policy come from their
 * owners.
 */
public record AcceptTaskReq(
        @JsonProperty(required = true) @Min(0) @Max(Long.MAX_VALUE - 1) long expectedRevision,
        @JsonProperty(required = true) @Min(1) long expectedBindingRevision,
        @JsonProperty(required = true) @Min(1) long expectedScopeRevision,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100)
                List<@NotNull @Valid SlotSelection> slots) {
    /** Duplicate selections cannot designate two independent accepts of the same frozen role. */
    @AssertTrue
    @JsonIgnore
    public boolean isUniqueSlots() {
        if (slots == null) {
            return true;
        }
        var keys = new HashSet<String>();
        for (var slot : slots) {
            if (slot != null && !keys.add(slot.requirementNo() + ":" + slot.ordinal())) {
                return false;
            }
        }
        return true;
    }

    public record SlotSelection(
            @JsonProperty(required = true)
                    @NotBlank
                    @Size(max = 32)
                    @Pattern(regexp = "[^\\p{javaWhitespace}](?:[\\s\\S]*[^\\p{javaWhitespace}])?")
                    String requirementNo,
            @JsonProperty(required = true) @Min(0) @Max(99) int ordinal,
            @JsonProperty(required = true) @Min(0) @Max(Long.MAX_VALUE - 1)
                    long expectedAssignmentRevision) {}
}
