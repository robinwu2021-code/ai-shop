package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.annotation.JsonDeserialize;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentItemReq(
        String skuNo,
        @JsonProperty(required = true)
                @JsonDeserialize(using = CheckoutPort.CountDeserializer.class)
                int qty) {
    public AppointmentItemReq {
        if ((skuNo == null || skuNo.isBlank() || skuNo.length() > 32)
                || (qty < 1 || qty > 10)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** Unexpected nested assertions must not disappear before immutable replay matching. */
    @JsonAnySetter
    public void unknown(String field, Object value) {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
