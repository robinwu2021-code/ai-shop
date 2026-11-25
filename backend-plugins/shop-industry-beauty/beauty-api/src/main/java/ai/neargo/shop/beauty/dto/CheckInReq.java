package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record CheckInReq(String appointmentNo, ReceptionIdentityProof identityProof, Long expectedRevision,
        @JsonInclude(JsonInclude.Include.NON_NULL) String arrivalChallenge) {
    public CheckInReq(String appointmentNo, ReceptionIdentityProof identityProof, Long expectedRevision) {
        this(appointmentNo, identityProof, expectedRevision, null);
    }

    public CheckInReq {
        if (appointmentNo == null || appointmentNo.isBlank() || appointmentNo.length() > 32
                || identityProof == null || expectedRevision == null || expectedRevision < 0
                || arrivalChallenge != null && !arrivalChallenge.matches("[A-Za-z0-9_-]{43}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "CheckInReq[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
