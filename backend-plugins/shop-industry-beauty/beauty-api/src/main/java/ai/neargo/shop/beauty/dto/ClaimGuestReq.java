package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Identity evidence is transient; it never authorizes spending. */
public record ClaimGuestReq(String verifiedIdentity, String claimToken) {
    public ClaimGuestReq {
        if (verifiedIdentity == null || !verifiedIdentity.matches("cr1_[A-Za-z0-9_-]{43}")
                || claimToken == null || !claimToken.matches("[A-Za-z0-9_-]{43}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "ClaimGuestReq[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
