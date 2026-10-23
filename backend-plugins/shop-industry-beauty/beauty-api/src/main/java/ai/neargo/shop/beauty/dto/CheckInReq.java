package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record CheckInReq(String appointmentNo, ReceptionIdentityProof identityProof, Long expectedRevision) {
    public CheckInReq {
        if (appointmentNo == null || appointmentNo.isBlank() || appointmentNo.length() > 32
                || identityProof == null || expectedRevision == null || expectedRevision < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "CheckInReq[REDACTED]"; }
}
