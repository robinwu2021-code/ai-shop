package ai.neargo.shop.beauty.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Coordinator proposes one exact STAFF segment; the incoming SELF must accept it. */
public record HandoverTaskReq(
        @Positive long expectedScopeRevision,
        @Min(0) long expectedTaskRevision,
        @Positive long expectedBindingRevision,
        @Positive long expectedOccupationRevision,
        @NotBlank @Size(max = 32) String segmentNo,
        @NotBlank @Size(max = 32) String newResourceNo,
        @NotBlank @Size(max = 32) String newPersonnelNo,
        @NotBlank @Size(max = 500) String reason) {}
