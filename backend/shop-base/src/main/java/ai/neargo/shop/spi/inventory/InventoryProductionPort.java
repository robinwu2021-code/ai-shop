package ai.neargo.shop.spi.inventory;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Original production event identity is the only posting key; a query never creates stock
 * documents.
 */
public interface InventoryProductionPort {
    record Line(String itemRef, BigDecimal quantity, String unit, String definitionHash) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown production line field");
        }
    }

    record Command(
            String entityNo,
            String storeNo,
            String eventNo,
            String purpose,
            Instant occurredAt,
            List<Line> lines,
            @JsonInclude(JsonInclude.Include.NON_NULL) PlanProof planProof) {
        public Command(
                String entityNo,
                String storeNo,
                String eventNo,
                String purpose,
                Instant occurredAt,
                List<Line> lines) {
            this(entityNo, storeNo, eventNo, purpose, occurredAt, lines, null);
        }

        public Command {
            lines = List.copyOf(lines);
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown production command field");
        }
    }

    record SourceLeaf(String leafRef, BigDecimal quantity) {}

    record PlanProof(
            InventoryReservationPort.Scope scope,
            String planHash,
            String sourceComponentRef,
            List<SourceLeaf> leaves) {
        public PlanProof {
            leaves = List.copyOf(leaves);
        }
    }

    record Reverse(Command original, String reverseEventNo, String reason) {}

    record IssuedLine(
            String leafRef,
            String itemRef,
            BigDecimal quantity,
            String unit,
            String documentNo,
            String ledgerRef,
            Long costMinor,
            String currency,
            String batchNo,
            String valuationStatus) {}

    record Receipt(
            String eventNo,
            String requestHash,
            String status,
            String documentNo,
            List<Line> lines,
            Long costMinor,
            String currency,
            List<IssuedLine> issuedLines) {
        public Receipt(
                String eventNo,
                String requestHash,
                String status,
                String documentNo,
                List<Line> lines,
                Long costMinor,
                String currency) {
            this(eventNo, requestHash, status, documentNo, lines, costMinor, currency, List.of());
        }

        public Receipt {
            lines = List.copyOf(lines);
            issuedLines = List.copyOf(issuedLines);
        }
    }

    /**
     * Requires a trusted source event, supported base-unit precision and existing material/store
     * mapping.
     */
    Receipt consume(Command command);

    /**
     * ABSENT is distinct from successful consumption; repeat commands must match the entire
     * original payload.
     */
    Receipt query(Command command);

    default Receipt reverse(Reverse command) {
        throw new UnsupportedOperationException("Production reversal unavailable");
    }
}
