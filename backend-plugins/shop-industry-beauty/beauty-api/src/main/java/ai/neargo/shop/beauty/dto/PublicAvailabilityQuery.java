package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record PublicAvailabilityQuery(String storeNo, List<AppointmentItemReq> items, OffsetDateTime from,
        OffsetDateTime to) {
    public PublicAvailabilityQuery {
        if ((storeNo == null || storeNo.isBlank() || storeNo.length() > 32)
                || (items == null || items.isEmpty() || items.size() > 10 || items.stream().anyMatch(Objects::isNull))
                || (from == null || to == null || !to.isAfter(from))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        items = List.copyOf(items);
    }
}
