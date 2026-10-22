package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Identity evidence is transient; it never authorizes spending. */
public record ClaimGuestReq(String verifiedIdentity, String claimToken) {
    public ClaimGuestReq {
        if (verifiedIdentity == null || !verifiedIdentity.matches("cr1_[A-Za-z0-9_-]{43}")
                || claimToken == null || !claimToken.matches("[A-Za-z0-9_-]{43}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "ClaimGuestReq[REDACTED]"; }
}
