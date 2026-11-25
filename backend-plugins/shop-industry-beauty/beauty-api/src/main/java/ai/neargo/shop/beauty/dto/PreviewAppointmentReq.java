package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record PreviewAppointmentReq(String storeNo, String customerRef, List<AppointmentItemReq> items,
        List<AppointmentAssignmentReq> requestedAssignments, OffsetDateTime startAt, String guestNo,
        String requestedPersonnelNo, String memberNo) {
    public PreviewAppointmentReq(String storeNo, String customerRef, List<AppointmentItemReq> items,
            List<AppointmentAssignmentReq> requestedAssignments, OffsetDateTime startAt, String guestNo,
            String requestedPersonnelNo) {
        this(storeNo, customerRef, items, requestedAssignments, startAt, guestNo, requestedPersonnelNo, null);
    }

    public PreviewAppointmentReq(String storeNo, String customerRef, List<AppointmentItemReq> items,
            List<AppointmentAssignmentReq> requestedAssignments, OffsetDateTime startAt) {
        this(storeNo, customerRef, items, requestedAssignments, startAt, null, null);
    }

    public PreviewAppointmentReq(String storeNo, String customerRef, List<AppointmentItemReq> items,
            List<AppointmentAssignmentReq> requestedAssignments, OffsetDateTime startAt, String guestNo) {
        this(storeNo, customerRef, items, requestedAssignments, startAt, guestNo, null);
    }

    public PreviewAppointmentReq {
        if (memberNo != null && (!memberNo.matches("[A-Za-z0-9_-]{1,64}") || guestNo != null || customerRef != null)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (guestNo != null && (guestNo.isBlank() || guestNo.length() > 32 || customerRef != null)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if ((storeNo == null || storeNo.isBlank() || storeNo.length() > 32)
                || (items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull))
                || (startAt == null)
                || (customerRef != null && (customerRef.isBlank() || customerRef.length() > 256))
                || (requestedPersonnelNo != null && (requestedPersonnelNo.isBlank()
                        || requestedPersonnelNo.length() > 32
                        || requestedAssignments != null && !requestedAssignments.isEmpty()))
                || (requestedAssignments != null && requestedAssignments.size() > 30)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        items = List.copyOf(items);
        requestedAssignments = requestedAssignments == null ? List.of() : List.copyOf(requestedAssignments);
    }
}
