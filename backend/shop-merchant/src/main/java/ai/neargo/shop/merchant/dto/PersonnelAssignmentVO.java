package ai.neargo.shop.merchant.dto;

/** Persisted business result for durable command replay; no operation grants another identity. */
public record PersonnelAssignmentVO(String personnelNo, long revision, String assignmentNo) { }
