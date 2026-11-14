package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderTaskVO(String taskNo, String appointmentItemNo, long scopeRevision, String skuNo,
        String definitionVersionNo, BigDecimal qty, BigDecimal doneQty, BigDecimal voidedQty, String uom,
        String status, long bindingRevision, String assignmentState, String sourceState, boolean canStart,
        OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, OffsetDateTime actualStartedAt,
        List<AppointmentAssignmentVO> assignments, WorkOrderTaskSnapshotVO taskSnapshot, List<WorkOrderStepVO> steps,
        List<WorkOrderParticipationVO> participations, List<String> availableActions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long revision,
        @JsonInclude(JsonInclude.Include.NON_NULL) String preparationOperationNo) {
    /** Existing readers can still construct historical projections without claiming a current CAS. */
    public WorkOrderTaskVO(String taskNo, String appointmentItemNo, long scopeRevision, String skuNo,
            String definitionVersionNo, BigDecimal qty, BigDecimal doneQty, BigDecimal voidedQty, String uom,
            String status, long bindingRevision, String assignmentState, String sourceState, boolean canStart,
            OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, OffsetDateTime actualStartedAt,
            List<AppointmentAssignmentVO> assignments, WorkOrderTaskSnapshotVO taskSnapshot,
            List<WorkOrderStepVO> steps, List<WorkOrderParticipationVO> participations, List<String> availableActions) {
        this(taskNo, appointmentItemNo, scopeRevision, skuNo, definitionVersionNo, qty, doneQty, voidedQty,
                uom, status, bindingRevision, assignmentState, sourceState, canStart, plannedStartAt, plannedEndAt,
                actualStartedAt, assignments, taskSnapshot, steps, participations, availableActions, null, null);
    }
    /** A historical version-aware reader need not manufacture a preparation reference. */
    public WorkOrderTaskVO(String taskNo, String appointmentItemNo, long scopeRevision, String skuNo,
            String definitionVersionNo, BigDecimal qty, BigDecimal doneQty, BigDecimal voidedQty, String uom,
            String status, long bindingRevision, String assignmentState, String sourceState, boolean canStart,
            OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, OffsetDateTime actualStartedAt,
            List<AppointmentAssignmentVO> assignments, WorkOrderTaskSnapshotVO taskSnapshot,
            List<WorkOrderStepVO> steps, List<WorkOrderParticipationVO> participations, List<String> availableActions,
            Long revision) {
        this(taskNo, appointmentItemNo, scopeRevision, skuNo, definitionVersionNo, qty, doneQty, voidedQty,
                uom, status, bindingRevision, assignmentState, sourceState, canStart, plannedStartAt, plannedEndAt,
                actualStartedAt, assignments, taskSnapshot, steps, participations, availableActions, revision, null);
    }
    public WorkOrderTaskVO { assignments = List.copyOf(assignments); steps = List.copyOf(steps);
        participations = List.copyOf(participations); availableActions = List.copyOf(availableActions); }
}
