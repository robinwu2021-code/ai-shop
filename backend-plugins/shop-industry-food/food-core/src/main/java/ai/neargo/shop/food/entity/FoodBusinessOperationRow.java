package ai.neargo.shop.food.entity;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Exact primary-source projection for evidence hashing, with typed column readers. */
public final class FoodBusinessOperationRow {
    private final Map<String, Object> columns;

    public FoodBusinessOperationRow(Map<String, Object> values) {
        var normalized = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        columns = Collections.unmodifiableMap(normalized);
    }

    @JsonValue
    public Map<String, Object> snapshot() {
        return columns;
    }

    public Object get(String column) {
        return columns.get(column);
    }

    public String text(String column) {
        Object value = get(column);
        return value == null ? null : (String) value;
    }

    public long number(String column) {
        return ((Number) get(column)).longValue();
    }

    public BigDecimal decimal(String column) {
        return (BigDecimal) get(column);
    }

    public Instant instant(String column) {
        Object value = get(column);
        return value instanceof Timestamp time
                ? time.toLocalDateTime().toInstant(ZoneOffset.UTC)
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
