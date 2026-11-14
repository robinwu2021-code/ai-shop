package ai.neargo.shop.pay.storedvalue.port;

import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.pay.entitlement.policy.FundingCapabilityPolicy;
import org.springframework.beans.factory.ObjectProvider;

/** Registered alongside the verified credit/use owners; opt-ins restrict those implementations, not create them. */
public class StoredValueCapabilityPortImpl implements StoredValueCapabilityPort {
    private final PayEntitlementProperties properties;
    private final ObjectProvider<EntitlementUsePort> uses;
    private final ObjectProvider<StoredValueUseQuotePort> quotes;
    public StoredValueCapabilityPortImpl(PayEntitlementProperties properties) { this(properties, null, null); }
    public StoredValueCapabilityPortImpl(PayEntitlementProperties properties, ObjectProvider<EntitlementUsePort> uses,
            ObjectProvider<StoredValueUseQuotePort> quotes) {
        this.properties = properties; this.uses = uses; this.quotes = quotes;
    }
    @Override public PricingFundingCapabilities.Snapshot pricing(PricingFundingCapabilities.Scope scope) {
        return FundingCapabilityPolicy.inspect(PricingFundingCapabilities.Family.STORED_VALUE, scope,
                properties.getStoredValue().isEnabled() && properties.getEntitlement().getUse().isEnabled(),
                uses != null && uses.orderedStream().limit(2).count() == 1
                        && quotes != null && quotes.orderedStream().limit(2).count() == 1);
    }
    @Override public Status status(String tenantNo) {
        ContractChecks.id(tenantNo);
        boolean credit="MAIN".equals(tenantNo) && properties.getStoredValue().isEnabled();
        return new Status(tenantNo,credit,credit && properties.getEntitlement().getUse().isEnabled());
    }
}
