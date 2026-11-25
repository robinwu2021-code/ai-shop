package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Reception evidence is transient and never authorizes a resource or payment effect by itself. */
public record ReceptionIdentityProof(String customerRef, String guestNo, String claimToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String deskPhone,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberNo) {
    public ReceptionIdentityProof(String customerRef, String guestNo, String claimToken) {
        this(customerRef, guestNo, claimToken, null, null);
    }
    public ReceptionIdentityProof {
        int modes = (customerRef == null ? 0 : 1) + (guestNo == null ? 0 : 1)
                + (deskPhone == null ? 0 : 1) + (memberNo == null ? 0 : 1);
        if (modes != 1 || (guestNo == null && claimToken != null)
                || customerRef != null
                        && (!customerRef.matches("cr1_[A-Za-z0-9_-]{43}") || claimToken != null)
                || deskPhone != null && !deskPhone.matches("09[0-9]{8}")
                || memberNo != null && !memberNo.matches("[A-Za-z0-9_-]{1,64}")
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
