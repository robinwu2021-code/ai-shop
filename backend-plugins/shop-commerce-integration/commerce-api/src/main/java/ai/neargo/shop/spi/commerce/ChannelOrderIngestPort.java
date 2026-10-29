package ai.neargo.shop.spi.commerce;

import java.time.Instant;
import java.util.List;

/**
 * The original industry/core owner prepares an idempotent import before any platform acceptance.
 */
public interface ChannelOrderIngestPort {
    ChannelSourceValidationPort.Owner owner();

    Prepared prepareImport(Command command);

    Prepared getImport(Reference reference);

    Accepted confirmAcceptance(Confirmation confirmation);

    Cancelled cancelPreparedImport(Reference reference, String reason);

    record MappedSelection(
            String externalLineRef,
            String kind,
            ChannelResourceMappingPort.Resolution mapping,
            String quantity,
            long amountMinor) {}

    record MappedLine(
            String externalLineRef,
            ChannelResourceMappingPort.Resolution offer,
            ChannelResourceMappingPort.Resolution sku,
            String quantity,
            String unit,
            List<MappedSelection> selections) {
        public MappedLine {
            selections = List.copyOf(selections);
        }
    }

    record Command(
            String importNo,
            String entityNo,
            String storeNo,
            String connectionNo,
            String connectionRevisionNo,
            String externalSnapshotNo,
            ChannelOrderGateway.Snapshot original,
            List<MappedLine> lines) {
        public Command {
            lines = List.copyOf(lines);
        }
    }

    record Reference(String importNo, String entityNo, String storeNo, String documentHash) {}

    /**
     * COMMITTED has a real core acceptance; ACCEPTED additionally has the original industry
     * binding.
     */
    enum Status {
        PREPARED,
        WAITING_OWNER,
        COMMITTED,
        ACCEPTED,
        CANCELLED
    }

    /**
     * The original owner's deadline bounds inventory/funding readiness; old providers cannot
     * authorize SEND.
     */
    record Prepared(
            Reference reference, Status status, String preparationReceiptRef, Instant validUntil) {
        public Prepared(Reference reference, Status status, String preparationReceiptRef) {
            this(reference, status, preparationReceiptRef, null);
        }
    }

    record Cancelled(Reference reference, String cancellationReceiptRef) {}

    record Confirmation(Reference reference, String platformAcceptanceReceiptRef) {}

    /**
     * Opaque coreItemRef is resolved by the original core owner; it is never a public database ID.
     */
    record LineAllocation(
            String externalLineRef,
            String parentExternalLineRef,
            String kind,
            String componentRef,
            String sourceLineRef,
            String coreItemRef,
            String quantity,
            String unit,
            long amountMinor,
            String currency,
            String allocationEvidenceRef) {}

    record Accepted(
            Reference reference,
            String orderNo,
            String businessDocumentNo,
            String acceptanceReceiptRef,
            List<LineAllocation> allocations) {
        public Accepted {
            allocations = List.copyOf(allocations);
        }

        public Accepted(
                Reference reference,
                String orderNo,
                String businessDocumentNo,
                String acceptanceReceiptRef) {
            this(reference, orderNo, businessDocumentNo, acceptanceReceiptRef, List.of());
        }
    }
}
