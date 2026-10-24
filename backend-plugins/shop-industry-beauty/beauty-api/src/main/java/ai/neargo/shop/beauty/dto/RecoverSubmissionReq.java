package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/** Explicit recovery accepts a reason and original local revision, never new owner credentials. */
public record RecoverSubmissionReq(
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) long expectedRevision,
        String reason) {
    public RecoverSubmissionReq {
        if (expectedRevision < 0 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        reason = reason.strip();
    }

    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
