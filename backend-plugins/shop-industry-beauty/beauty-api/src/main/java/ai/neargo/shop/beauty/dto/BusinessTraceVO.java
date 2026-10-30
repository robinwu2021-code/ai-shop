package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record BusinessTraceVO(
        String businessNo,
        String workOrderNo,
        String appointmentNo,
        long scopeRevision,
        List<ServiceSubmissionVO> submissions,
        WorkOrderBillVO bill,
        boolean complete,
        List<String> blockingReasons) {
    public BusinessTraceVO {
        submissions = List.copyOf(submissions);
        blockingReasons = List.copyOf(blockingReasons);
    }
}
