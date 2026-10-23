package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;

/** Opening confirms a checked-in scope; empty assignments retain the complete arrangement. */
public record OpenAppointmentWorkOrderReq(Long expectedRevision, List<AppointmentAssignmentReq> assignments,
        OffsetDateTime startAt) {
    public OpenAppointmentWorkOrderReq {
        if (expectedRevision == null || expectedRevision < 0) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
    public OpenAppointmentWorkOrderReq(Long expectedRevision, List<AppointmentAssignmentReq> assignments) {
        this(expectedRevision, assignments, null);
    }
}
