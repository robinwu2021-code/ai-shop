package ai.neargo.shop.payclient.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayOwnerRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A missing or foreign source reply is an error, never permission to probe a legacy coupon implementation. */
@Component
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteEntitlementOwnerRegistryPort implements EntitlementOwnerRegistryPort {
    private final PayInternalApi pay;
    public RemoteEntitlementOwnerRegistryPort(PayInternalApi pay) { this.pay=pay; }
    @Override public OwnerRef resolve(EntitlementContext ctx,SourceIdentity source) {
        var result=RemoteEntitlementCalls.call(() -> pay.entitlementOwnerResolve(new PayOwnerRequests.Resolve(ctx,source)));
        if(!source.equals(result.source())) throw BizException.of(ErrorCode.CONFLICT);
        return result;
    }
}
