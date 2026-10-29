package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/** Identity evidence is transient; it never authorizes spending. */
public record RegisterGuestReq(
        String contact,
        String displayName,
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) boolean consent) {
    public RegisterGuestReq {
        if (!consent || contact != null && contact.length() > 128 || displayName != null && displayName.length() > 80) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    @Override public String toString() { return "RegisterGuestReq[REDACTED]"; }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
