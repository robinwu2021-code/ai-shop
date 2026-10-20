package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;

/** Bound with the verified owner implementation in embedded and standalone runtimes. */
public class EntitlementCapabilityPortImpl implements EntitlementCapabilityPort {
    private final PayEntitlementProperties properties;
    public EntitlementCapabilityPortImpl(PayEntitlementProperties properties) { this.properties = properties; }

    @Override public Status status(String tenantNo) {
        ContractChecks.id(tenantNo);
        boolean supported = "MAIN".equals(tenantNo);
        var entitlement = properties.getEntitlement();
        return new Status(tenantNo, supported && entitlement.getCatalog().isEnabled(),
                supported && entitlement.getIssue().isEnabled(), supported && entitlement.getUse().isEnabled(),
                supported && properties.getPaidCard().isEnabled());
    }
}
