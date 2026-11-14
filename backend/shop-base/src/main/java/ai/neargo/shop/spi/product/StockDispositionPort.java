package ai.neargo.shop.spi.product;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;

/** Original owner evidence, independent from queued commands or Pay confirmation. */
public interface StockDispositionPort {
    /** Reserve only the frozen original so retries cannot route through a changed primary owner. */
    default ReservationView reserveOriginal(Reservation request) {
        throw new UnsupportedOperationException("original reservation unavailable");
    }

    /** Actual complete held rows are required before a staged order can be finalized. */
    default ReservationView observeReservation(ReservationLookup request) {
        throw new UnsupportedOperationException("original reservation unavailable");
    }

    /** Compensation accepts only complete actual terminal rows from the frozen original owner. */
    default ReleaseView releaseOriginal(ReservationLookup request) {
        throw new UnsupportedOperationException("original reservation release unavailable");
    }

    /** UNKNOWN or incomplete owner lines never become terminal release success. */
    record ReleaseView(
            Original original,
            String reservationOperationNo,
            String outcome,
            List<OwnerLine> lines,
            String observationHash,
            Instant observedAt,
            String reasonCode) {
        public ReleaseView {
            StockDispositionContract.requireReservationOperation(original, reservationOperationNo);
            lines = List.copyOf(lines);
            if (!List.of("RELEASED", "REJECTED", "UNKNOWN").contains(outcome)
                    || observedAt == null
                    || observationHash == null
                    || !observationHash.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid original release evidence");
            }
            if ("RELEASED".equals(outcome)) {
                var owners = new HashSet<String>();
                if (lines.isEmpty()
                        || lines.stream()
                                .anyMatch(
                                        line ->
                                                line == null
                                                        || line.ownerRowNo() == null
                                                        || !owners.add(line.ownerRowNo())
                                                        || line.originalQuantity() <= 0
                                                        || line.revision() < 0
                                                        || !(line.disposition()
                                                                        == CurrentState.RELEASED
                                                                || line.disposition()
                                                                        == CurrentState.EXPIRED)
                                                        || line.allocatedQuantity() != 0
                                                        || line.consumedQuantity() != 0
                                                        || line.releasedQuantity()
                                                                != line.originalQuantity())
                        || !StockDispositionContract.manifestHash(
                                        StockDispositionContract.needs(
                                                lines.stream()
                                                        .map(
                                                                line ->
                                                                        new StockPort.SkuQty(
                                                                                line.skuNo(),
                                                                                line
                                                                                        .originalQuantity(),
                                                                                line
                                                                                        .sourceStoreNo(),
                                                                                line
                                                                                        .fulfillmentRef(),
                                                                                original
                                                                                        .lifecycleVersion()))
                                                        .toList()))
                                .equals(original.manifestHash())) {
                    throw new IllegalArgumentException(
                            "release original is not completely terminal");
                }
            }
        }
    }

    record Reservation(Original original, String reservationOperationNo) {
        public Reservation {
            StockDispositionContract.requireReservationOperation(original, reservationOperationNo);
        }
    }

    record ReservationLookup(Original original, String reservationOperationNo) {
        public ReservationLookup {
            StockDispositionContract.requireReservationOperation(original, reservationOperationNo);
        }
    }

    record ReservationView(
            Original original,
            String reservationOperationNo,
            String outcome,
            List<OwnerLine> lines,
            String observationHash,
            Instant observedAt,
            String reasonCode) {
        public ReservationView {
            StockDispositionContract.requireReservationOperation(original, reservationOperationNo);
            lines = List.copyOf(lines);
            if (!List.of("RESERVED", "REJECTED", "UNKNOWN").contains(outcome)
                    || observedAt == null
                    || observationHash == null
                    || !observationHash.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid reservation observation");
            }
            if ("RESERVED".equals(outcome)) {
                var owners = new HashSet<String>();
                if (lines.isEmpty()
                        || lines.stream()
                                .anyMatch(
                                        line ->
                                                line == null
                                                        || line.ownerRowNo() == null
                                                        || !owners.add(line.ownerRowNo())
                                                        || line.disposition()
                                                                != CurrentState.RESERVED
                                                        || line.originalQuantity() <= 0
                                                        || line.revision() < 0
                                                        || line.allocatedQuantity() != 0
                                                        || line.consumedQuantity() != 0
                                                        || line.releasedQuantity() != 0)
                        || !StockDispositionContract.manifestHash(
                                        StockDispositionContract.needs(
                                                lines.stream()
                                                        .map(
                                                                line ->
                                                                        new StockPort.SkuQty(
                                                                                line.skuNo(),
                                                                                line
                                                                                        .originalQuantity(),
                                                                                line
                                                                                        .sourceStoreNo(),
                                                                                line
                                                                                        .fulfillmentRef(),
                                                                                original
                                                                                        .lifecycleVersion()))
                                                        .toList()))
                                .equals(original.manifestHash())) {
                    throw new IllegalArgumentException(
                            "reservation original is not completely held");
                }
            }
        }
    }

    /** Claim/confirm only the same immutable original, then observe its actual owner rows. */
    Disposition confirmOriginal(Confirmation command);

    /** Observe without manufacturing missing reservations or rebinding today's authority. */
    Disposition observeOriginal(Lookup query);

    enum Outcome {
        PENDING,
        APPLIED,
        REJECTED,
        UNKNOWN
    }

    enum CurrentState {
        RESERVED,
        ALLOCATED,
        CONSUMED,
        RELEASED,
        EXPIRED,
        MISSING,
        INCONSISTENT
    }

    /** Canonical complete source allocation, never supplied by a public receipt body. */
    record Original(
            String tenantNo,
            String entityNo,
            String orderNo,
            String authority,
            int lifecycleVersion,
            String manifestHash,
            List<Need> needs) {
        public Original {
            needs = List.copyOf(needs);
            if (!"MAIN".equals(tenantNo)
                    || entityNo == null
                    || entityNo.isBlank()
                    || orderNo == null
                    || orderNo.isBlank()
                    || !List.of("PLATFORM", "DUAL", "INVENTORY").contains(authority)
                    || lifecycleVersion != 1 && lifecycleVersion != 2
                            && !(lifecycleVersion == 3 && "INVENTORY".equals(authority))
                    || !StockDispositionContract.manifestHash(needs).equals(manifestHash)) {
                throw new IllegalArgumentException("invalid original stock manifest");
            }
        }
    }

    /** Source store differs from an owner's resolved effective pool/location. */
    record Need(String skuNo, String sourceStoreNo, String fulfillmentRef, int quantity) {
        public Need {
            if (skuNo == null
                    || skuNo.isBlank()
                    || skuNo.length() > 64
                    || quantity <= 0
                    || sourceStoreNo != null
                            && (sourceStoreNo.isBlank() || sourceStoreNo.length() > 32)
                    || fulfillmentRef == null
                    || fulfillmentRef.isBlank()
                    || fulfillmentRef.length() > 64) {
                throw new IllegalArgumentException("invalid stock source need");
            }
        }
    }

    record Confirmation(Original original, String operationNo) {
        public Confirmation {
            StockDispositionContract.requireOperation(original, operationNo);
        }
    }

    record Lookup(Original original, String operationNo) {
        public Lookup {
            StockDispositionContract.requireOperation(original, operationNo);
        }
    }

    /**
     * All original rows, including released/missing/inconsistent evidence, are observed. Revision
     * is the platform lock row version or inventory reservation aggregate disposition revision.
     */
    record OwnerLine(
            String ownerRowNo,
            String skuNo,
            String sourceStoreNo,
            String fulfillmentRef,
            String ownerNo,
            String itemNo,
            String locationNo,
            Boolean presale,
            int originalQuantity,
            int allocatedQuantity,
            int consumedQuantity,
            int releasedQuantity,
            long revision,
            CurrentState disposition) {}

    record Disposition(
            Original original,
            String operationNo,
            Outcome outcome,
            CurrentState state,
            List<OwnerLine> lines,
            String observationHash,
            Instant observedAt,
            String reasonCode) {
        public Disposition {
            lines = List.copyOf(lines);
        }
    }
}
