package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Original operating facts preserve policy time and business-day exceptions for trusted consumers.
 */
public final class StoreCalendarSourceContracts {
    private StoreCalendarSourceContracts() {}

    public record Query(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = DateDeserializer.class) LocalDate fromDate,
            @JsonDeserialize(using = DateDeserializer.class) LocalDate toDate) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    public record Store(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String status,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String openHours) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    public record Policy(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String policyNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String timezone,
            @JsonDeserialize(using = IntegerDeserializer.class) Integer cutoverMinute,
            Instant effectiveFrom,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    public record ExceptionDay(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String calendarNo,
            @JsonDeserialize(using = DateDeserializer.class) LocalDate businessDate,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String mode,
            @JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class)
                    List<String> intervals,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String savedPolicyNo,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version) {
        public ExceptionDay {
            intervals = intervals == null ? null : List.copyOf(intervals);
        }

        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    public record Facts(
            @JsonDeserialize(using = IntegerDeserializer.class) Integer schemaVersion,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String purpose,
            Query query,
            Store store,
            List<Policy> policies,
            List<ExceptionDay> exceptions,
            Instant verifiedAt,
            Instant validUntil,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String receiptNo) {
        public Facts {
            policies = policies == null ? null : List.copyOf(policies);
            exceptions = exceptions == null ? null : List.copyOf(exceptions);
        }

        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    public static final class IntegerDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return c.reportInputMismatch(Integer.class, "Integer token required");
            return p.getIntValue();
        }
    }

    public static final class DateDeserializer extends ValueDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_STRING
                    || !p.getString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                return c.reportInputMismatch(LocalDate.class, "ISO date string required");
            try {
                return LocalDate.parse(p.getString());
            } catch (RuntimeException invalid) {
                return c.reportInputMismatch(LocalDate.class, "Invalid date");
            }
        }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown original calendar field");
    }
}
