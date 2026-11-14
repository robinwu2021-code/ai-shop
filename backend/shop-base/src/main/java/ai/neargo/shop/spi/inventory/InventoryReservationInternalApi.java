package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.spi.inventory.InventoryReservationPort.Line;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Snapshot;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Authenticated PLATFORM transport for the existing inventory domain; it declares no separate inventory process. */
public interface InventoryReservationInternalApi {
    String ROOT = "/internal/inventory/reservations";
    @PostExchange(ROOT + "/reserve") Snapshot reserve(@RequestBody Reserve request);
    @PostExchange(ROOT + "/accept-unpaid") Snapshot acceptUnpaid(@RequestBody Reference request);
    @PostExchange(ROOT + "/allocate-paid") Snapshot allocatePaid(@RequestBody Reference request);
    @PostExchange(ROOT + "/issue") Snapshot issue(@RequestBody Issue request);
    @PostExchange(ROOT + "/cancel-all") Snapshot cancelAll(@RequestBody Reference request);
    @PostExchange(ROOT + "/query") Snapshot query(@RequestBody Reference request);

    record Reference(Scope scope) {
        public Reference { if (scope == null) throw new IllegalArgumentException("Reservation scope required"); }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    record Reserve(Scope scope, List<Line> lines,
                   @JsonDeserialize(using = InventoryReservationPort.LongValue.class) long ttlSeconds,
                   @JsonDeserialize(using = InventoryReservationPort.IntegerValue.class) int lifecycleVersion) {
        public Reserve {
            if (scope == null || lines == null || lines.isEmpty() || lines.size() > 256
                    || ttlSeconds <= 0 || lifecycleVersion < 1 || lifecycleVersion > 3) {
                throw new IllegalArgumentException("Invalid inventory reserve request");
            }
            lines = List.copyOf(lines);
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    record Issue(Scope scope,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String fulfillmentRef) {
        public Issue {
            if (scope == null || !MaterialCatalogFacts.validKey(fulfillmentRef, 64)) {
                throw new IllegalArgumentException("Reservation fulfillment required");
            }
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { unknown(); }
    }
    private static void unknown() { throw new IllegalArgumentException("Unknown reservation request field"); }
}
