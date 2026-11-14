package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Backend-only tax source. Caller must authenticate access to the supplied source order/cart scope. */
public interface PricingTaxSettingPort {
    MerchantTaxSnapshot resolve(Scope scope);

    record Scope(String tenantNo, String entityNo, String storeNo) {
        public Scope {
            ContractChecks.id(tenantNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
        }
    }
}
