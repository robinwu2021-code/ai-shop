package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentAssignmentReq(
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) int itemIndex,
        String requirementNo,
        String resourceNo,
        String personnelNo) {
    public AppointmentAssignmentReq {
        if ((itemIndex < 0 || itemIndex > 9)
                || (requirementNo == null || requirementNo.isBlank() || requirementNo.length() > 32)
                || (resourceNo == null && personnelNo == null)
                || (resourceNo != null && (resourceNo.isBlank() || resourceNo.length() > 32))
                || (personnelNo != null && (personnelNo.isBlank() || personnelNo.length() > 32))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** Unknown assertions cannot be discarded before authorization or immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
