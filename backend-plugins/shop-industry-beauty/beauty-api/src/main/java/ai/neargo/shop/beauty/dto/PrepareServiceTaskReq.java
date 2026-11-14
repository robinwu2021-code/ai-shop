package ai.neargo.shop.beauty.dto;

import jakarta.validation.constraints.Min;

/** Revision guards only; original units, actor, reference and stock manifests are server-owned. */
public record PrepareServiceTaskReq(
        @Min(0) long expectedTaskRevision,
        @Min(1) long expectedBindingRevision,
        @Min(1) long expectedScopeRevision) {}
