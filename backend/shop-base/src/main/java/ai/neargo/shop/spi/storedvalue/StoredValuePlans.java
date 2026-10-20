package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;

/** Store-owned fixed recharge offers; no activity reference or caller-supplied gift on collection. */
public final class StoredValuePlans {
    private StoredValuePlans() { }

    public record Create(String storeNo, String name, String currency, long paidAmountMinor, long bonusAmountMinor) {
        public Create {
            ContractChecks.id(storeNo); ContractChecks.currency(currency);
            ContractChecks.require(name != null && !name.isBlank() && name.equals(name.strip()) && name.length() <= 100);
            ContractChecks.require(paidAmountMinor > 0 && bonusAmountMinor >= 0
                    && paidAmountMinor <= Long.MAX_VALUE - bonusAmountMinor);
        }
        public long totalMinor() { return paidAmountMinor + bonusAmountMinor; }
    }

    public record View(String planNo, String versionNo, long revision, String storeNo, String name,
            String currency, long paidAmountMinor, long bonusAmountMinor, long totalAmountMinor,
            String status, Instant createdAt) { }

    public record Status(String storeNo, String status, long expectedRevision) {
        public Status {
            ContractChecks.id(storeNo);
            ContractChecks.require(("ACTIVE".equals(status) || "INACTIVE".equals(status)) && expectedRevision >= 0);
        }
    }
}
