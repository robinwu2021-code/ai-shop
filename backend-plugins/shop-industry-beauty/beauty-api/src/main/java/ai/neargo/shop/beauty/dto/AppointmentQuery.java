package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentQuery(String storeNo, OffsetDateTime from, OffsetDateTime to, String status, String
        personnelNo, Integer page, Integer size, String assignmentState) { }
