package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentAssignmentReq(int itemIndex, String requirementNo, String resourceNo, String personnelNo) {
    public AppointmentAssignmentReq {
        if ((itemIndex < 0 || itemIndex > 9)
                || (requirementNo == null || requirementNo.isBlank() || requirementNo.length() > 32)
                || (resourceNo == null && personnelNo == null)
                || (resourceNo != null && (resourceNo.isBlank() || resourceNo.length() > 32))
                || (personnelNo != null && (personnelNo.isBlank() || personnelNo.length() > 32))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
