package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.OffsetDateTime;
import java.util.List;

/** Customer proof is transient; unassigned scopes must not imply a resource reservation. */
public record OpenWorkOrderReq(String customerRef, String storeNo, List<AppointmentItemReq> items,
        String appointmentNo, String guestNo, Long expectedRevision, List<AppointmentAssignmentReq> assignments,
        OffsetDateTime startAt) {
    public OpenWorkOrderReq {
        items = items == null ? List.of() : List.copyOf(items);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        if (storeNo == null || storeNo.isBlank() || storeNo.length() > 32
                || appointmentNo != null && (appointmentNo.isBlank() || appointmentNo.length() > 32)
                || guestNo != null && (guestNo.isBlank() || guestNo.length() > 32)
                || customerRef != null && (customerRef.isBlank() || customerRef.length() > 256)
                || customerRef != null && guestNo != null
                || appointmentNo != null && (expectedRevision == null || expectedRevision < 0)
                || appointmentNo == null && ((customerRef == null) == (guestNo == null) || items.isEmpty()
                    || !assignments.isEmpty() || startAt != null || expectedRevision != null)
                || items.size() > 10 || items.stream().mapToInt(AppointmentItemReq::qty).sum() > 10) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    public OpenWorkOrderReq(String customerRef, String storeNo, List<AppointmentItemReq> items, String appointmentNo) {
        this(customerRef, storeNo, items, appointmentNo, null, null, List.of(), null);
    }
    @Override public String toString() { return "OpenWorkOrderReq[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
