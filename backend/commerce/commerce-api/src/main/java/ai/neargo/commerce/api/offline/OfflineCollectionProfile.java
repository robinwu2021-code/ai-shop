package ai.neargo.commerce.api.offline;

import java.util.Map;

/** Current administrative state, including disabled profiles. Never frozen into a quote. */
public record OfflineCollectionProfile(String profileNo, long profileVersion, String platformMerchantNo,
        String currency, String receivingAccountKey, String paymentWindowVersion, long paymentWindowMs,
        Map<String, String> instructions, boolean enabled) {
    public OfflineCollectionProfile(OfflineCollectionTerms terms, boolean enabled) {
        this(terms.profileNo(), terms.profileVersion(), terms.platformMerchantNo(), terms.currency(),
                terms.receivingAccountKey(), terms.paymentWindowVersion(), terms.paymentWindowMs(), terms.instructions(), enabled);
    }
}
