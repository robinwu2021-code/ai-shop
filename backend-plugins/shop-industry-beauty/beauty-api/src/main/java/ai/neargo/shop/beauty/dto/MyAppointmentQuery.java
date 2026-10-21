package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record MyAppointmentQuery(OffsetDateTime from, OffsetDateTime to, String status, Integer page, Integer size) { }
