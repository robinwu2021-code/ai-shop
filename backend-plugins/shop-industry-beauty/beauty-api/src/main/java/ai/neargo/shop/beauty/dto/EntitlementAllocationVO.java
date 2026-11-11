package ai.neargo.shop.beauty.dto;

/** Public references allow reconciliation with the original member record without exposing its authorization. */
public record EntitlementAllocationVO(String claimNo, String taskNo, String operationNo, String bindingNo,
                                     String usageNo, int quantity, String status) {}
