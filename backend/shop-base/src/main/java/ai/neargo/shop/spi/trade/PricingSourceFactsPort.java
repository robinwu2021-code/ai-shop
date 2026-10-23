package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingUnit;
import java.util.List;

/** Authorized source prices only; these facts do not admit promotions, taxes, funds or order submission. */
public interface PricingSourceFactsPort {
    String sourceSystem();
    Snapshot staff(Scope scope, PricingSourceRef source);
    Snapshot customer(PricingSourceRef source, Credentials credentials);

    /** Server-resolved staff scope; constructing this value grants no merchant or store access. */
    record Scope(String tenantNo, String entityNo, String storeNo) {
        public Scope {
            PricingSourceFacts.key(tenantNo); PricingSourceFacts.key(entityNo); PricingSourceFacts.key(storeNo);
            if (!"MAIN".equals(tenantNo)) throw new IllegalArgumentException("Existing MAIN tenant required");
        }
    }
    /** Passed to the source authority on every read; never serialized into a pricing snapshot. */
    record Credentials(String sessionNo, String accessToken, String buyerNo) {
        public Credentials {
            if (sessionNo != null) PricingSourceFacts.key(sessionNo);
            if (buyerNo != null) PricingSourceFacts.key(buyerNo);
            if (accessToken != null && (accessToken.isBlank() || accessToken.length() > 512)) {
                throw new IllegalArgumentException("Bounded source credentials required");
            }
            if (accessToken == null && buyerNo == null) throw new IllegalArgumentException("Source identity required");
        }
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
    enum Kind { MAIN, ADDON, BUNDLE_INCLUDED, BUNDLE_ADD, BUNDLE_DIFFERENCE }
    record Component(Kind kind, PricingUnit unit) { }
    record InvoiceBasis(String invoiceLineRef, List<PricingPortion> portions, MoneyMinor amount) {
        public InvoiceBasis { portions = portions == null ? null : List.copyOf(portions); }
    }
    record Snapshot(int schemaVersion, Scope scope, String subjectNo, PricingSourceRef source, String channel,
            PricingFacts prices, List<Component> composition, List<InvoiceBasis> invoiceBases, String snapshotHash) {
        public Snapshot {
            composition = composition == null ? null : List.copyOf(composition);
            invoiceBases = invoiceBases == null ? null : List.copyOf(invoiceBases);
        }
    }
}
