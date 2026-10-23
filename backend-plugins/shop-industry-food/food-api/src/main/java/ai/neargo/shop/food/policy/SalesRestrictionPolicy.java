package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Shared public and remote validation prevents quota mutation or ownership coercion. */
public final class SalesRestrictionPolicy {
    private SalesRestrictionPolicy() { }
    public static void command(SalesRestrictionReq r) {
        require(r != null); MenuDefinitionPolicy.key(r.skuNo());
        require(r.mode() != null && List.of("SOLD_OUT", "QUOTA", "PREORDER_LIMIT", "CLEAR").contains(r.mode()));
        require(r.reason() != null && !r.reason().isBlank() && r.reason().equals(r.reason().strip())
                && r.reason().length() <= 255 && r.reason().codePoints().noneMatch(Character::isISOControl));
        scope(r.orderSource(), r.fulfillment());
        if ("CLEAR".equals(r.mode())) {
            MenuDefinitionPolicy.key(r.restrictionNo());
            require(r.version() != null && r.version() >= 0 && r.version() < Long.MAX_VALUE
                    && r.quota() == null && r.businessDate() == null && r.startsAt() == null && r.expiresAt() == null);
        } else {
            require(r.restrictionNo() == null && r.version() == null);
            require("SOLD_OUT".equals(r.mode()) ? r.quota() == null && r.businessDate() == null : positive(r.quota()));
            require(r.startsAt() == null || r.expiresAt() == null || r.expiresAt().isAfter(r.startsAt()));
            require(r.startsAt() == null || r.startsAt().getNano() % 1000000 == 0);
            require(r.expiresAt() == null || r.expiresAt().getNano() % 1000000 == 0);
        }
    }
    public static void query(SalesRestrictionQuery q) {
        if (q == null) return;
        require((q.page() == null || q.page() >= 1) && (q.size() == null || q.size() >= 1 && q.size() <= 100));
        optionalKey(q.skuNo()); require(q.status() == null
                || List.of("ACTIVE", "CLEARED", "EXPIRED").contains(q.status()));
    }
    /** Null is an intentional wildcard; unknown spellings must never create ineffective ACTIVE rules. */
    public static void scope(String source, String fulfillment) {
        require(source == null || MenuContextDefinitions.supportedSource(source));
        require(fulfillment == null || MenuContextDefinitions.supportedFulfillment(fulfillment));
        require(!"CHANNEL".equals(source) || fulfillment == null || "DELIVERY".equals(fulfillment));
    }
    public static void optionalKey(String value) { if (value != null) MenuDefinitionPolicy.key(value); }
    public static boolean valid(SalesRestrictionVO v, String store) {
        if (v == null || !store.equals(v.storeNo()) || v.version() == null || v.version() < 0
                || v.startsAt() == null || v.status() == null
                || !List.of("ACTIVE", "CLEARED", "EXPIRED").contains(v.status())) return false;
        try {
            MenuDefinitionPolicy.key(v.restrictionNo());
            command(new SalesRestrictionReq(v.skuNo(), v.mode(), null, null, v.quota(), v.businessDate(),
                    v.startsAt(), v.expiresAt(), v.reason(), v.orderSource(), v.fulfillment()));
            return nonnegative(v.reservedQty()) && nonnegative(v.consumedQty())
                    && (v.quota() == null ? v.reservedQty().signum() == 0 && v.consumedQty().signum() == 0
                            : v.reservedQty().add(v.consumedQty()).compareTo(v.quota()) <= 0);
        } catch (RuntimeException invalid) { return false; }
    }
    private static boolean positive(BigDecimal value) { return nonnegative(value) && value.signum() > 0; }
    private static boolean nonnegative(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.scale() <= 6 && value.precision() - value.scale() <= 12;
    }
    private static void require(boolean condition) { if (!condition) throw BizException.of(ErrorCode.BAD_REQUEST); }
    /** Create omits CAS; CLEAR requires a real nonnegative integer through command validation. */
    public static final class OptionalVersionDeserializer extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT || p.getLongValue() < 0) {
                return c.reportInputMismatch(Long.class, "Restriction version requires a nonnegative integer");
            }
            return p.getLongValue();
        }
    }
    /** Boundaries are UTC instants, never epoch numbers, local datetimes or silently truncated microseconds. */
    public static final class InstantDeserializer extends ValueDeserializer<Instant> {
        @Override public Instant deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_STRING) {
                return c.reportInputMismatch(Instant.class, "Restriction time requires an ISO instant string");
            }
            try {
                var value = Instant.parse(p.getString());
                if (value.getNano() % 1000000 != 0) {
                    return c.reportInputMismatch(Instant.class, "Restriction time requires millisecond precision");
                }
                return value;
            } catch (DateTimeParseException invalid) {
                return c.reportInputMismatch(Instant.class, "Restriction time requires a timezone-qualified instant");
            }
        }
    }
    /** Decimal quota uses JSON number tokens without string coercion or binary rounding. */
    public static final class DecimalDeserializer extends ValueDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT && p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
                return c.reportInputMismatch(BigDecimal.class, "Quota requires a numeric token");
            }
            return p.getDecimalValue();
        }
    }
}
