package ai.neargo.shop.pay.storedvalue.port;

import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;

/** Registered alongside the verified credit/use owners; opt-ins restrict those implementations, not create them. */
public class StoredValueCapabilityPortImpl implements StoredValueCapabilityPort {
    private final PayEntitlementProperties properties;
    public StoredValueCapabilityPortImpl(PayEntitlementProperties properties) { this.properties=properties; }
    @Override public Status status(String tenantNo) {
        ContractChecks.id(tenantNo);
        boolean credit="MAIN".equals(tenantNo) && properties.getStoredValue().isEnabled();
        return new Status(tenantNo,credit,credit && properties.getEntitlement().getUse().isEnabled());
    }
}
