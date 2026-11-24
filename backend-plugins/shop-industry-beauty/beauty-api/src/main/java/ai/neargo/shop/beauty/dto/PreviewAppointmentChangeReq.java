package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record PreviewAppointmentChangeReq(Long expectedRevision, List<AppointmentItemReq> items, OffsetDateTime
        startAt, List<AppointmentAssignmentReq> assignments, String requestedPersonnelNo) {
    public PreviewAppointmentChangeReq(Long expectedRevision, List<AppointmentItemReq> items,
            OffsetDateTime startAt, List<AppointmentAssignmentReq> assignments) {
        this(expectedRevision, items, startAt, assignments, null);
    }

    public PreviewAppointmentChangeReq {
        if ((items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull))
                || (startAt == null)
                || (expectedRevision == null || expectedRevision < 0)
                || (requestedPersonnelNo != null && (requestedPersonnelNo.isBlank()
                        || requestedPersonnelNo.length() > 32 || assignments != null && !assignments.isEmpty()))
                || (assignments != null && assignments.size() > 30)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        items = List.copyOf(items);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
}
