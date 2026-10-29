package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentAssignmentVO(String appointmentItemNo, String requirementNo, String personnelNo, String
        resourceNo, OffsetDateTime startAt, OffsetDateTime endAt) { }
