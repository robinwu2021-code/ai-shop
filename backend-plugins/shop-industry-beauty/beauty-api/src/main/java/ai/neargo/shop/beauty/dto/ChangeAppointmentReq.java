package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record ChangeAppointmentReq(String previewNo, Long expectedRevision, String reason, String consentRef) {
    public ChangeAppointmentReq {
        if ((previewNo == null || previewNo.isBlank() || previewNo.length() > 32)
                || (expectedRevision == null || expectedRevision < 0)
                || (reason != null && (reason.isBlank() || reason.length() > 500))
                || (consentRef != null && consentRef.length() > 128)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
