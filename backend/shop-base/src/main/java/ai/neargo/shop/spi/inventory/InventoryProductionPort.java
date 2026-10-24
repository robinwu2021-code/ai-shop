package ai.neargo.shop.spi.inventory;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Original production event identity is the only posting key; a query never creates stock documents. */
public interface InventoryProductionPort {
    record Line(String itemRef, BigDecimal quantity, String unit, String definitionHash) {
        @JsonAnySetter public void rejectUnknown(String name,Object value) {
            throw new IllegalArgumentException("Unknown production line field");
        }
    }
    record Command(String entityNo, String storeNo, String eventNo, String purpose, Instant occurredAt,
            List<Line> lines) {
        public Command { lines = List.copyOf(lines); }
        @JsonAnySetter public void rejectUnknown(String name,Object value) {
            throw new IllegalArgumentException("Unknown production command field");
        }
    }
    record Receipt(String eventNo, String requestHash, String status, String documentNo,
            List<Line> lines, Long costMinor, String currency) {
        public Receipt { lines = List.copyOf(lines); }
    }
    /** Requires a trusted source event, supported base-unit precision and existing material/store mapping. */
    Receipt consume(Command command);
    /** ABSENT is distinct from successful consumption; repeat commands must match the entire original payload. */
    Receipt query(Command command);
}
