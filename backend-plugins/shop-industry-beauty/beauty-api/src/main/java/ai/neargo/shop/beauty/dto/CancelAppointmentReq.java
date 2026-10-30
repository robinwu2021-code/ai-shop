package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record CancelAppointmentReq(Long expectedRevision, String reason) {
    public CancelAppointmentReq {
        if ((expectedRevision == null || expectedRevision < 0)
                || (reason == null || reason.isBlank() || reason.length() > 500)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
