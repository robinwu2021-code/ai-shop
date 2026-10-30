package ai.neargo.shop.beauty.dto;

/** Immutable requirement reference, shared by frozen appointment and eventual task scope. */
public record WorkOrderRequirementVO(String requirementNo, String roleCode, String resourceType, String skillGoodsNo,
        int qty, int sequenceNo, int offsetMin, int durationMin, String selectionRule) { }
