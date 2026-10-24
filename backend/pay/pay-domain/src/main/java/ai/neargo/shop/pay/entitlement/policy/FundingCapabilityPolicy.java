package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;

/** Conservative verified subsets of the existing owners; no new funding execution is enabled here. */
public final class FundingCapabilityPolicy {
    private FundingCapabilityPolicy() { }
    public static Snapshot inspect(Family family, Scope scope, boolean enabled, boolean uniqueOwners) {
        Status status;
        if (!"MAIN".equals(scope.tenantNo())) status = Status.TENANT_UNSUPPORTED;
        else if (!"CORE".equals(scope.sourceSystem())
                || !PricingFundingCapabilities.sourceType(family).equals(scope.sourceType())) {
            status = Status.SOURCE_UNSUPPORTED;
        } else if (!"CNY".equals(scope.currency())) status = Status.CURRENCY_UNSUPPORTED;
        else if (!enabled) status = Status.DISABLED;
        else if (!uniqueOwners) status = Status.OWNER_UNAVAILABLE;
        else status = Status.SUPPORTED;
        return PricingFundingCapabilities.freeze(family, scope, status);
    }
}
