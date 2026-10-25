package ai.neargo.shop.spi.product;

import java.time.Instant;
import java.util.List;

/** Original owner evidence, independent from queued commands or Pay confirmation. */
public interface StockDispositionPort {
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
