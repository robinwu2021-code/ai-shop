package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Original availability authority; absence of a provider is explicitly unsupported. */
public interface ChannelAvailabilitySourcePort {
    enum Kind {
        STOCK,
        STORE
    }

    enum State {
        QUANTITY,
        OPEN,
        CLOSED,
        SUSPENDED,
        UNKNOWN,
        UNSUPPORTED
    }

    enum StoreMode {
        PHYSICAL_OPEN,
        ACCEPTING_ORDERS
    }

    record Query(
            String entityNo,
            String storeNo,
            Kind kind,
            String resourceRef,
            @JsonInclude(JsonInclude.Include.NON_NULL) Owner owner,
            @JsonInclude(JsonInclude.Include.NON_NULL) StoreMode storeMode) {
        public Query(String entityNo, String storeNo, Kind kind, String resourceRef) {
            this(entityNo, storeNo, kind, resourceRef, null, null);
        }
    }

    record Data(
            Query query,
            State state,
            String quantity,
            String unit,
            String sourceRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant nextChangeAt) {
        public Data(Query query, State state, String quantity, String unit, String sourceRevision) {
            this(query, state, quantity, unit, sourceRevision, null);
        }
    }

    record Facts(Data data, Instant observedAt, Instant validUntil, String originalReceiptRef) {}

    Kind kind();

    /**
     * Shared inventory is owner-independent; original STORE providers must name FOOD or RETAIL
     * explicitly.
     */
    default Owner owner() {
        return null;
    }

    Facts capture(Query query);
}
