package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderVO(String workOrderNo, String appointmentNo, long scopeRevision, String status,
        String submissionNo,
        String sourceState, String operationNo, String operationState, List<String> taskNos,
        OffsetDateTime plannedStartAt) {
    public WorkOrderVO { taskNos = List.copyOf(taskNos); }
}
