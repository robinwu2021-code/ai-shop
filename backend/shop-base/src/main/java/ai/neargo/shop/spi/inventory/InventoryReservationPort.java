package ai.neargo.shop.spi.inventory;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Trusted internal inventory commands. The caller must authorize its actual batch/store and actor first;
 * an entity-bound reservation reference grants no public actor or store authorization.
 * Only existing AISHOP SKU/store mappings are accepted; quantities are whole inventory base units.
 */
public interface InventoryReservationPort {
    record Scope(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String tenantNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String externalRef) {
        public Scope { key(tenantNo, 32); key(entityNo, 32); key(externalRef, 64); }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }

    record Line(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = IntegerValue.class) int qty,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String fulfillmentRef) {
        public Line { key(skuNo, 32); key(storeNo, 32); positive(qty); key(fulfillmentRef, 64); }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }

    /** Original store cannot be reconstructed after warehouse remapping, and no warehouse ID is disclosed. */
    record ReservedLine(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = IntegerValue.class) int qty,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String fulfillmentRef) {
        public ReservedLine { key(skuNo, 32); positive(qty); if (fulfillmentRef != null) key(fulfillmentRef, 64); }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }

    enum State {
        ABSENT, HELD, ACCEPTED_UNPAID, PARTIAL_UNPAID, ISSUED_UNPAID,
        ALLOCATED, PARTIALLY_COMMITTED, COMMITTED, PARTIAL_RELEASED, RELEASED, EXPIRED
    }

    record Snapshot(Scope scope, String reservationId, State state, int lifecycleVersion,
                    boolean paymentAcknowledged, List<ReservedLine> originalLines) {
        public Snapshot {
            if (scope == null || state == null || originalLines == null) throw new IllegalArgumentException();
            originalLines = List.copyOf(originalLines);
            if (state == State.ABSENT) {
                if (reservationId != null || lifecycleVersion != 0 || paymentAcknowledged || !originalLines.isEmpty()) {
                    throw new IllegalArgumentException("Invalid absent reservation facts");
                }
            } else {
                key(reservationId, 32);
                if (lifecycleVersion < 1 || lifecycleVersion > 3) throw new IllegalArgumentException();
            }
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }

    Snapshot reserve(Scope scope, List<Line> lines, long ttlSeconds, int lifecycleVersion);
    Snapshot acceptUnpaid(Scope scope);
    Snapshot allocatePaid(Scope scope);
    Snapshot issue(Scope scope, String fulfillmentRef);
    /** Cancels all unissued quantities belonging to this owner/reference, across its original stores. */
    Snapshot cancelAll(Scope scope);
    Snapshot query(Scope scope);

    private static void key(String value, int max) {
        if (!MaterialCatalogFacts.validKey(value, max)) throw new IllegalArgumentException("Invalid reservation key");
    }
    private static void positive(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("Inventory quantities must be positive whole base units");
    }
    private static void unknown() { throw new IllegalArgumentException("Unknown reservation field"); }

    /** TTL is also transported as an exact integer, rather than rounded by the JSON mapper. */
    final class LongValue extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "Inventory TTL must be an integer token");
            }
            return parser.getLongValue();
        }
    }

    /** Fractions, numeric strings, overflow and null cannot become whole inventory quantities. */
    final class IntegerValue extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "Inventory value must be an integer token");
            }
            return parser.getIntValue();
        }
    }
}
