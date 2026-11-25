package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record CreateAppointmentReq(String previewNo, String customerRef, List<String> sourceRefs,
        String consentRef, String guestNo, @JsonInclude(JsonInclude.Include.NON_NULL) String memberNo) {
    public CreateAppointmentReq(String previewNo, String customerRef, List<String> sourceRefs,
            String consentRef, String guestNo) {
        this(previewNo, customerRef, sourceRefs, consentRef, guestNo, null);
    }

    public CreateAppointmentReq(String previewNo, String customerRef, List<String> sourceRefs,
        String consentRef) {
        this(previewNo, customerRef, sourceRefs, consentRef, null);
    }

    public CreateAppointmentReq {
        if (memberNo != null && (!memberNo.matches("[A-Za-z0-9_-]{1,64}") || guestNo != null || customerRef != null)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (guestNo != null && (guestNo.isBlank() || guestNo.length() > 32 || customerRef != null)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if ((previewNo == null || previewNo.isBlank() || previewNo.length() > 32)
                || (customerRef != null && (customerRef.isBlank() || customerRef.length() > 256))
                || (sourceRefs != null && sourceRefs.size() > 50)
                || (consentRef != null && consentRef.length() > 128)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
