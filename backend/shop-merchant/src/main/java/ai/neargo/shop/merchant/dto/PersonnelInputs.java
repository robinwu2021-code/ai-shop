package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Consistent bounded maintenance input; no client field can declare a tenant or operator. */
final class PersonnelInputs {
    private PersonnelInputs() { }
    static void require(boolean condition) {
        if (!condition) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    static String text(String value, int max, boolean optional) {
        if (optional && value == null) { return null; }
        require(value != null && !value.isBlank() && value.length() <= max && value.equals(value.trim()));
        return value;
    }
    static void revision(Long value) { require(value != null && value >= 0 && value < Long.MAX_VALUE); }
    static OffsetDateTime utc(OffsetDateTime value, boolean optional) {
        if (optional && value == null) { return null; }
        require(value != null && value.getYear() >= 1000 && value.getYear() <= 9998);
        return value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
