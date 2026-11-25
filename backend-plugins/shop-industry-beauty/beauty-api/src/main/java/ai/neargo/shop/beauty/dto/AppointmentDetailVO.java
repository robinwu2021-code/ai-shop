package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentDetailVO(AppointmentSummaryVO appointment, List<AppointmentPlanItemVO> items,
        List<AppointmentAssignmentVO> assignments, List<String> availableActions, DeskCustomer deskCustomer,
        String workOrderNo) {
    public AppointmentDetailVO(AppointmentSummaryVO appointment, List<AppointmentPlanItemVO> items,
            List<AppointmentAssignmentVO> assignments, List<String> availableActions) {
        this(appointment, items, assignments, availableActions, null, null);
    }
    public record DeskCustomer(String displayName, String phoneLastFour) { }
}
