package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** A null arrangementRevision denotes preserved legacy data without a verified current arrangement. */
public record AppointmentSummaryVO(String appointmentNo, String storeNo, long revision, String status,
        OffsetDateTime startAt, OffsetDateTime endAt, Long arrangementRevision) { }
