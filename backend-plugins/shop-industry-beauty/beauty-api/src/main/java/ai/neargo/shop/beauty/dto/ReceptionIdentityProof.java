package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record ReceptionIdentityProof(String customerRef, String guestNo, String claimToken) {
    public ReceptionIdentityProof {
        if ((customerRef == null) == (guestNo == null)
                || customerRef != null
                        && (!customerRef.matches("cr1_[A-Za-z0-9_-]{43}") || claimToken != null)
                || guestNo != null
                        && (guestNo.isBlank()
                                || guestNo.length() > 32
                                || claimToken == null
                                || !claimToken.matches("[A-Za-z0-9_-]{43}"))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "ReceptionIdentityProof[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
