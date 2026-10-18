package ai.neargo.shop.spi.user;

import java.util.Set;

/** Explicit tenant/owner facts for write preflight, separate from the consumer-facing store directory. */
public interface MerchantScopeFactsPort {
    boolean entityActive(String tenantNo, String entityNo);

    /** READONLY stores may remain in configuration; live transaction eligibility is a separate decision. */
    boolean storesOwned(String tenantNo, String entityNo, Set<String> storeNos);
}
