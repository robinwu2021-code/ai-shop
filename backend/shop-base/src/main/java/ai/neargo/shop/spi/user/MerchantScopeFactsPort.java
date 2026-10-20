package ai.neargo.shop.spi.user;

import java.util.Set;

/** Explicit tenant/owner facts for write preflight, separate from the consumer-facing store directory. */
public interface MerchantScopeFactsPort {
    boolean entityActive(String tenantNo, String entityNo);

    /** Current active owner, persisted known industry and no expired qualification; never recommendation tags. */
    default boolean promotionIndustryAdmitted(String tenantNo, String entityNo) { return false; }

    /** READONLY stores may remain in configuration; live transaction eligibility is a separate decision. */
    boolean storesOwned(String tenantNo, String entityNo, Set<String> storeNos);
}
