package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record PreviewAppointmentReq(String storeNo, String customerRef, List<AppointmentItemReq> items,
        List<AppointmentAssignmentReq> requestedAssignments, OffsetDateTime startAt) {
    public PreviewAppointmentReq {
        if ((storeNo == null || storeNo.isBlank() || storeNo.length() > 32)
                || (items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull))
                || (startAt == null)
                || (customerRef != null && (customerRef.isBlank() || customerRef.length() > 256))
                || (requestedAssignments != null && requestedAssignments.size() > 30)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        items = List.copyOf(items);
        requestedAssignments = requestedAssignments == null ? List.of() : List.copyOf(requestedAssignments);
    }
}
