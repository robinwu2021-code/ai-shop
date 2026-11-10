package ai.neargo.shop.beauty.dto;

/** Published assignment is planning evidence, never acceptance or permission to start. */
public record ServiceTaskAssignmentVO(
        String submissionNo,
        String workOrderNo,
        String taskNo,
        long resultTaskRevision,
        long directPlanRevision,
        String planHash,
        boolean canStart) {
    public ServiceTaskAssignmentVO {
        if (canStart) throw new IllegalArgumentException("planning cannot authorize start");
    }
}
