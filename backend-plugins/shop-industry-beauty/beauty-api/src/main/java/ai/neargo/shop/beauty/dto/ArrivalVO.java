package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Arrival confirms presence only; no work order or payment is implied. */
public record ArrivalVO(String appointmentNo, long revision, String status, OffsetDateTime checkedInAt,
        String timing, OffsetDateTime plannedStartAt, OffsetDateTime plannedEndAt, String operationNo,
        String operationState) { }
