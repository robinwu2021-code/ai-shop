package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentDetailVO(AppointmentSummaryVO appointment, List<AppointmentPlanItemVO> items,
        List<AppointmentAssignmentVO> assignments, List<String> availableActions) { }
