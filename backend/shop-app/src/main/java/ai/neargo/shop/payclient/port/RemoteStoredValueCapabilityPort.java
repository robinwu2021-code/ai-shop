package ai.neargo.shop.payclient.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Missing or mismatched owner facts cannot attest activity publication readiness. */
@Component
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteStoredValueCapabilityPort implements StoredValueCapabilityPort {
    private final PayInternalApi pay;
    public RemoteStoredValueCapabilityPort(PayInternalApi pay) { this.pay=pay; }
    @Override public Status status(String tenantNo) {
        var status=RemoteEntitlementCalls.call(() -> pay.storedValueCapabilities(
                new PayStoredValueRequests.Capabilities(tenantNo)));
        if(status==null || !tenantNo.equals(status.tenantNo())) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        return status;
    }
    @Override public PricingFundingCapabilities.Snapshot pricing(PricingFundingCapabilities.Scope scope) {
        var value = RemoteEntitlementCalls.call(() -> pay.storedPricingCapabilities(new PayCapabilityRequests.Pricing(scope)));
        try { PricingFundingCapabilities.validate(value, PricingFundingCapabilities.Family.STORED_VALUE, scope); }
        catch (RuntimeException invalid) { throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
        return value;
    }
}
