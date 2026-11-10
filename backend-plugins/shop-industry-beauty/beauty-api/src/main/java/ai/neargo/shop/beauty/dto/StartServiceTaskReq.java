package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;

/** Actual start is separate from quantity/stock preparation and SELF acceptance. */
public record StartServiceTaskReq(
        @Positive long expectedScopeRevision,
        @Min(0) long expectedTaskRevision,
        @Positive long expectedBindingRevision,
        @NotEmpty @Size(max = 100) @Valid List<@NotNull Slot> ownSlots) {
    @AssertTrue
    @JsonIgnore
    public boolean isUniqueSlots() {
        if (ownSlots == null) return true;
        var selected = new HashSet<String>();
        for (var slot : ownSlots)
            if (slot != null && !selected.add(slot.requirementNo() + ":" + slot.ordinal()))
                return false;
        return true;
    }

    public record Slot(
            @NotBlank @Size(max = 32) String requirementNo,
            @Min(0) int ordinal,
            @Positive long assignmentRevision) {}
}
