package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AvailabilityQuery(String storeNo, List<AppointmentItemReq> items, OffsetDateTime from, OffsetDateTime
        to, String requestedPersonnelNo, String replaceAppointmentNo, Integer slotIntervalMinutes) {
    public AvailabilityQuery(String storeNo, List<AppointmentItemReq> items, OffsetDateTime from, OffsetDateTime to,
            String requestedPersonnelNo) {
        this(storeNo, items, from, to, requestedPersonnelNo, null, null);
    }

    public AvailabilityQuery {
        if ((storeNo == null || storeNo.isBlank() || storeNo.length() > 32)
                || (items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull))
                || (requestedPersonnelNo != null && (requestedPersonnelNo.isBlank() ||
                        requestedPersonnelNo.length() > 32))
                || (replaceAppointmentNo != null && (replaceAppointmentNo.isBlank() || replaceAppointmentNo.length() > 64))
                || (slotIntervalMinutes != null && (slotIntervalMinutes < 5 || slotIntervalMinutes > 60
                        || slotIntervalMinutes % 5 != 0))
                || (from == null || to == null || !to.isAfter(from))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        items = List.copyOf(items);
    }
}
