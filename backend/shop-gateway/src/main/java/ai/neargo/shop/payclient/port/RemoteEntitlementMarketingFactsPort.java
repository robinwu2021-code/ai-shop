package ai.neargo.shop.payclient.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayMarketingRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A mismatched remote reply cannot be projected as another merchant's marketing effect. */
@Component
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteEntitlementMarketingFactsPort implements EntitlementMarketingFactsPort {
    private final PayInternalApi pay;
    public RemoteEntitlementMarketingFactsPort(PayInternalApi pay) { this.pay=pay; }
    @Override public UsageEffects usage(EntitlementContext ctx,String entity,String no,String after,int limit) {
        var result=RemoteEntitlementCalls.call(() -> pay.marketingUsage(new PayMarketingRequests.Usage(ctx,entity,no,
                after,limit)));
        if(!ctx.tenantNo().equals(result.tenantNo()) || !entity.equals(result.entityNo()) || !no.equals(result.usageNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return result;
    }
}
