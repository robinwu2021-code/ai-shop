package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderVO(String workOrderNo, String appointmentNo, long scopeRevision, String status,
        String submissionNo,
        String sourceState, String operationNo, String operationState, List<String> taskNos,
        OffsetDateTime plannedStartAt, @JsonInclude(JsonInclude.Include.NON_NULL) Long revision) {
    /** Old receipts retain their serialized shape; a missing CAS is never synthesized. */
    public WorkOrderVO(String workOrderNo, String appointmentNo, long scopeRevision, String status,
            String submissionNo, String sourceState, String operationNo, String operationState,
            List<String> taskNos, OffsetDateTime plannedStartAt) {
        this(workOrderNo, appointmentNo, scopeRevision, status, submissionNo, sourceState, operationNo,
                operationState, taskNos, plannedStartAt, null);
    }
    public WorkOrderVO { taskNos = List.copyOf(taskNos); }
}
