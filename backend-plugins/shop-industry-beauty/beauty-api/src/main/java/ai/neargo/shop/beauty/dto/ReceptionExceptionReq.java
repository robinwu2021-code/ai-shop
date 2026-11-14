package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Set;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record ReceptionExceptionReq(String appointmentNo, String kind, String proposal, String reason,
        Long expectedRevision) {
    public ReceptionExceptionReq {
        if (appointmentNo == null
                || appointmentNo.isBlank()
                || appointmentNo.length() > 32
                || kind == null
                || !Set.of("EARLY", "LATE", "UNAVAILABLE", "CONFLICT", "IDENTITY_REQUIRED")
                        .contains(kind)
                || proposal == null
                || !Set.of("REQUEST_RESCHEDULE", "KEEP_WAITING", "REQUEST_CANCEL")
                        .contains(proposal)
                || reason == null
                || reason.isBlank()
                || reason.length() > 500
                || expectedRevision == null
                || expectedRevision < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "ReceptionExceptionReq[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
