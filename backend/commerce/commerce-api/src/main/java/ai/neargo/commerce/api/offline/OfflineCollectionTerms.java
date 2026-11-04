package ai.neargo.commerce.api.offline;

import java.util.Map;

/** Trusted immutable pay-domain version. Receiving instructions are disclosed only after durable issuance. */
public record OfflineCollectionTerms(String profileNo, long profileVersion, String platformMerchantNo,
        String currency, String receivingAccountKey, String paymentWindowVersion, long paymentWindowMs,
        Map<String, String> instructions) {
    public OfflineCollectionTerms {
        if (profileNo == null || profileNo.isBlank() || profileVersion < 1
                || platformMerchantNo == null || platformMerchantNo.isBlank()
                || currency == null || !currency.matches("[A-Z]{3}")
                || receivingAccountKey == null || receivingAccountKey.isBlank()
                || paymentWindowVersion == null || paymentWindowVersion.isBlank()
                || paymentWindowMs < 1000 || paymentWindowMs > 90L * 86400000
                || instructions == null || instructions.isEmpty())
            throw new IllegalArgumentException("OFFLINE_CONFIGURATION_INVALID");
        // Sorted, not Map.copyOf: its iteration order is salted per JVM, and this record is
        // serialized into hashed, cross-instance operation snapshots.
        instructions = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(instructions));
    }
}
