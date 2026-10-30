package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record PreviewMyAppointmentReq(String storeNo, List<AppointmentItemReq> items, OffsetDateTime startAt,
        String requestedPersonnelNo) {
    public PreviewMyAppointmentReq {
        if ((storeNo == null || storeNo.isBlank() || storeNo.length() > 32)
                || (items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull))
                || (startAt == null)
                || (requestedPersonnelNo != null && (requestedPersonnelNo.isBlank() ||
                        requestedPersonnelNo.length() > 32))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        items = List.copyOf(items);
    }
}
