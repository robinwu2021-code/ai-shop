package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentSummaryVO(String appointmentNo, String storeNo, long revision, String status,
        OffsetDateTime startAt, OffsetDateTime endAt, long arrangementRevision) { }
