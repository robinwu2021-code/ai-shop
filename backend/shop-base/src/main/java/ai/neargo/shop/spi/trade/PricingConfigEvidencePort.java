package ai.neargo.shop.spi.trade;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.common.pricing.PricingCapabilities;

/**
 * Backend-only publication evidence from real capability and tax owners; never a request DTO.
 * No default provider is supplied: absent real funding capability evidence must block publication.
 * Merchant-managed schema3 tax needs no platform review; verifiedTaxProfiles serves legacy schema2 only.
 */
public interface PricingConfigEvidencePort {
    /** Read verified owner versions for this exact scope without reserving assets; null means unavailable. */
    Evidence load(Scope scope);

    /** Initialize uses a template revision No; other commands use the existing template/config parent No. */
    record Scope(String tenantNo, String entityNo, String storeNo, String resourceNo, boolean template) { }

    /**
     * Sources must identify verified owner versions, not design fixtures or caller claims.
     * Reference and expiry belong to one stable evidence snapshot: do not renew them on each read,
     * since preview and publication compare the same snapshot. The API limits its remaining life to five minutes.
     */
    record Evidence(Scope scope, PricingCapabilities capabilities, String reference, Instant expiresAt,
            Set<String> verifiedTaxProfiles, List<PricingSourceRef> sources) {
        public Evidence {
            verifiedTaxProfiles = Set.copyOf(verifiedTaxProfiles);
            sources = List.copyOf(sources);
        }
    }
}
