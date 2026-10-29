package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record MyCheckInReq(String appointmentNo, CheckInProof checkInProof, Long expectedRevision) {
    public MyCheckInReq {
        if (appointmentNo == null || appointmentNo.isBlank() || appointmentNo.length() > 32
                || checkInProof == null || expectedRevision == null || expectedRevision < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "MyCheckInReq[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
