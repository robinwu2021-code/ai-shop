package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;

/** A trusted store receives a verified reference; tickets never enter durable reception payloads. */
public record ReceiveWalkInReq(String storeNo, String customerRef, String guestNo,
        List<AppointmentItemReq> items, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt) {
    public ReceiveWalkInReq {
        items = items == null ? List.of() : List.copyOf(items);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        if (storeNo == null || storeNo.isBlank() || storeNo.length() > 32
                || (customerRef == null) == (guestNo == null)
                || customerRef != null && (customerRef.isBlank() || customerRef.length() > 256)
                || guestNo != null && (guestNo.isBlank() || guestNo.length() > 32)
                || items.isEmpty() || items.size() > 10 || items.stream().mapToInt(AppointmentItemReq::qty).sum() > 10
                || assignments.size() > 30) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "ReceiveWalkInReq[REDACTED]"; }
}
