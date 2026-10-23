package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record CheckInProof(String memberCode, String arrivalChallenge) {
    public CheckInProof {
        if (memberCode == null || !memberCode.matches("mc1_[A-Za-z0-9_-]{43}") || arrivalChallenge == null
                || !arrivalChallenge.matches("[A-Za-z0-9_-]{43}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "CheckInProof[REDACTED]"; }
}
