package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.pay.entitlement.policy.FundingCapabilityPolicy;
import org.springframework.beans.factory.ObjectProvider;

/** Bound with the verified owner implementation in embedded and standalone runtimes. */
public class EntitlementCapabilityPortImpl implements EntitlementCapabilityPort {
    private final PayEntitlementProperties properties;
    private final ObjectProvider<EntitlementUsePort> uses;
    public EntitlementCapabilityPortImpl(PayEntitlementProperties properties) { this(properties, null); }
    public EntitlementCapabilityPortImpl(PayEntitlementProperties properties, ObjectProvider<EntitlementUsePort> uses) {
        this.properties = properties; this.uses = uses;
    }
    @Override public PricingFundingCapabilities.Snapshot pricing(PricingFundingCapabilities.Scope scope) {
        return FundingCapabilityPolicy.inspect(PricingFundingCapabilities.Family.ORDER_COUPON, scope,
                properties.getEntitlement().getUse().isEnabled(),
                uses != null && uses.orderedStream().limit(2).count() == 1);
    }

    @Override public Status status(String tenantNo) {
        ContractChecks.id(tenantNo);
        boolean supported = "MAIN".equals(tenantNo);
        var entitlement = properties.getEntitlement();
        return new Status(tenantNo, supported && entitlement.getCatalog().isEnabled(),
                supported && entitlement.getIssue().isEnabled(), supported && entitlement.getUse().isEnabled(),
                supported && properties.getPaidCard().isEnabled());
    }
}
