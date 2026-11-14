package ai.neargo.shop.beauty.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderTaskVO(String taskNo, String appointmentItemNo, long scopeRevision, String skuNo,
        String definitionVersionNo, BigDecimal qty, BigDecimal doneQty, BigDecimal voidedQty, String uom,
        String status, long bindingRevision, String assignmentState, String sourceState, boolean canStart,
        OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, OffsetDateTime actualStartedAt,
        List<AppointmentAssignmentVO> assignments, WorkOrderTaskSnapshotVO taskSnapshot, List<WorkOrderStepVO> steps,
        List<WorkOrderParticipationVO> participations, List<String> availableActions) {
    public WorkOrderTaskVO { assignments = List.copyOf(assignments); steps = List.copyOf(steps);
        participations = List.copyOf(participations); availableActions = List.copyOf(availableActions); }
}
