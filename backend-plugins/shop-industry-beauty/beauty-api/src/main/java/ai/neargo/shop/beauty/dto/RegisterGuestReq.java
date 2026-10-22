package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Identity evidence is transient; it never authorizes spending. */
public record RegisterGuestReq(String contact, String displayName, boolean consent) {
    public RegisterGuestReq {
        if (!consent || contact != null && contact.length() > 128 || displayName != null && displayName.length() > 80) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "RegisterGuestReq[REDACTED]"; }
}
