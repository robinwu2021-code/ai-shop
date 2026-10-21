package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentItemReq(String skuNo, int qty) {
    public AppointmentItemReq {
        if ((skuNo == null || skuNo.isBlank() || skuNo.length() > 32)
                || (qty < 1 || qty > 10)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
