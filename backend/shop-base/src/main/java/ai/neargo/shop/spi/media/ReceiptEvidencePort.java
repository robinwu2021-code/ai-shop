package ai.neargo.shop.spi.media;

import java.time.Instant;

/** Authoritative private attachment ownership and existence; never proof of bank settlement. */
public interface ReceiptEvidencePort {
    /** Verify the real current owner record and bytes outside any caller transaction. */
    ReceiptEvidence verify(ReceiptEvidenceQuery request);

    /** Trusted owner scope, not a client supplied URL or signature. */
    record ReceiptEvidenceQuery(
            String tenantNo,
            String entityNo,
            String storeNo,
            String actorAccountNo,
            String evidenceRef) {
        /** Restrict evidence lookup to the supported authoritative owner scope. */
        public ReceiptEvidenceQuery {
            if (!"MAIN".equals(tenantNo)) {
                throw new IllegalArgumentException("MAIN evidence required");
            }
            reference(entityNo, 32);
            reference(storeNo, 32);
            reference(actorAccountNo, 64);
            reference(evidenceRef, 255);
            if (evidenceRef.contains(":")
                    || evidenceRef.contains("..")
                    || evidenceRef.startsWith("/")) {
                throw new IllegalArgumentException("canonical asset key required");
            }
        }

        @Override
        public String toString() {
            return "ReceiptEvidenceQuery[redacted]";
        }
    }

    /** Immutable owner facts, excluding the original bytes and all reusable read credentials. */
    record ReceiptEvidence(
            String tenantNo,
            String assetKey,
            String entityNo,
            String storeNo,
            String uploadedByAccountNo,
            String bizType,
            long bytes,
            String contentType,
            Instant uploadedAt,
            String factHash) {
        @Override
        public String toString() {
            return "ReceiptEvidence[redacted]";
        }
    }

    private static void reference(String value, int length) {
        if (value == null
                || value.isBlank()
                || value.length() > length
                || !value.equals(value.strip())) {
            throw new IllegalArgumentException("canonical bounded evidence reference required");
        }
    }
}
