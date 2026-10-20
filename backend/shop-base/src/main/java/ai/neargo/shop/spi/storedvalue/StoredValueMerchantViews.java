package ai.neargo.shop.spi.storedvalue;

import java.time.Instant;

/** Internal read views: the merchant BFF enriches masked profiles and removes raw user numbers. */
public final class StoredValueMerchantViews {
    private StoredValueMerchantViews() { }
    public record Account(String accountRef, String userNo, String currency, long balanceMinor,
            long heldMinor, long availableMinor, Instant lastOccurredAt) { }
    public record Record(String recordNo, String userNo, String type, String title, String storeNo,
            String storeName, String currency, long signedAmountMinor, long balanceAfterMinor,
            Instant occurredAt, String businessNo, String planNo, String versionNo,
            Long paidAmountMinor, Long bonusAmountMinor) { }
}
