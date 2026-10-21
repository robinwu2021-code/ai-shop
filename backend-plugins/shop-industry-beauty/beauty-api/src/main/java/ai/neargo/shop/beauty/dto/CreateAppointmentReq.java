package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record CreateAppointmentReq(String previewNo, String customerRef, List<String> sourceRefs, String consentRef) {
    public CreateAppointmentReq {
        if ((previewNo == null || previewNo.isBlank() || previewNo.length() > 32)
                || (customerRef != null && (customerRef.isBlank() || customerRef.length() > 256))
                || (sourceRefs != null && sourceRefs.size() > 50)
                || (consentRef != null && consentRef.length() > 128)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
