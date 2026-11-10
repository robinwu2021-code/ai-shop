package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Expected versions are the observed current task and actual occupation, never plan versions. */
public record TaskWorkflowReq(
        @Positive long expectedScopeRevision,
        @Min(0) long expectedTaskRevision,
        @Positive long expectedBindingRevision,
        @Positive long expectedOccupationRevision,
        @NotNull Action action,
        @Size(max = 500) String reason,
        @Size(max = 32) String handoverProposalNo,
        @NotNull @Size(max = 1000) List<@NotBlank @Size(max = 32) String> cleaningSegmentNos) {
    @AssertTrue
    @JsonIgnore
    public boolean isActionShapeValid() {
        if (action == null || cleaningSegmentNos == null) return true;
        if (cleaningSegmentNos.stream().distinct().count() != cleaningSegmentNos.size())
            return false;
        if (action == Action.CLEAN)
            return !cleaningSegmentNos.isEmpty() && handoverProposalNo == null;
        return cleaningSegmentNos.isEmpty()
                && (action == Action.HANDOVER
                        ? handoverProposalNo != null && !handoverProposalNo.isBlank()
                        : handoverProposalNo == null);
    }

    public enum Action {
        PAUSE,
        RESUME,
        HANDOVER,
        END,
        CLEAN
    }
}
