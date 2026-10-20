package ai.neargo.shop.payclient.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementCapabilityPort implements EntitlementCapabilityPort {
    private final PayInternalApi pay;
    public RemoteEntitlementCapabilityPort(PayInternalApi pay) { this.pay = pay; }
    @Override public Status status(String tenantNo) {
        var status = RemoteEntitlementCalls.call(() -> pay.entitlementCapabilities(
                new PayCapabilityRequests.Status(tenantNo)));
        if (status == null || !tenantNo.equals(status.tenantNo())) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        return status;
    }
}
