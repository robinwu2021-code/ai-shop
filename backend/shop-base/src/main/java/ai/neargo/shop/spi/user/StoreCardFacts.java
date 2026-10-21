package ai.neargo.shop.spi.user;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Strict transport fields preserve unknown hours and refuse guessed owner, status, coordinates and ratings. */
public final class StoreCardFacts {
    private StoreCardFacts() { }
    public static boolean key(String v) {
        return v != null && !v.isBlank() && v.length() <= 32 && v.equals(v.strip())
                && v.codePoints().noneMatch(Character::isISOControl);
    }
    public static boolean valid(StoreDirectoryPort.StoreCard c) {
        return c != null && key(c.storeNo()) && key(c.entityNo()) && text(c.storeName(), 128, false)
                && Set.of("ACTIVE", "READONLY").contains(c.status() == null ? "" : c.status())
                && text(c.openHours(), 512, true) && text(c.address(), 1024, true) && text(c.logo(), 2048, true)
                && (c.latE6() == null || c.latE6() >= -90000000 && c.latE6() <= 90000000)
                && (c.lngE6() == null || c.lngE6() >= -180000000 && c.lngE6() <= 180000000)
                && Double.isFinite(c.rating()) && c.rating() >= 0 && c.rating() <= 5 && c.ratingCount() >= 0;
    }
    public static void response(Map<String, StoreDirectoryPort.StoreCard> cards, Collection<String> requested) {
        if (cards == null || cards.size() > requested.size() || cards.entrySet().stream().anyMatch(e ->
                !requested.contains(e.getKey()) || !valid(e.getValue()) ||
                        !e.getKey().equals(e.getValue().storeNo()))) {
            throw new IllegalStateException("Invalid original store-card response");
        }
    }
    private static boolean text(String value, int max, boolean optional) {
        return value == null ? optional : value.length() <= max && (optional || !value.isBlank())
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }
    public static final class TextDeserializer extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_STRING) {
                return c.reportInputMismatch(String.class, "Store-card text requires a string token");
            }
            return p.getString();
        }
    }
    public static final class BooleanDeserializer extends ValueDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() == JsonToken.VALUE_TRUE) return true;
            if (p.currentToken() == JsonToken.VALUE_FALSE) return false;
            return c.reportInputMismatch(Boolean.class, "Store-card flag requires a boolean token");
        }
    }
    public static final class IntegerDeserializer extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return c.reportInputMismatch(Integer.class, "Store-card counter requires an integer token");
            }
            return p.getIntValue();
        }
    }
    public static final class RatingDeserializer extends ValueDeserializer<Double> {
        @Override public Double deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT && p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
                return c.reportInputMismatch(Double.class, "Store-card rating requires a numeric token");
            }
            double result = p.getDoubleValue();
            if (!Double.isFinite(result)) return c.reportInputMismatch(Double.class, "Store rating must be finite");
            return result;
        }
    }
}
