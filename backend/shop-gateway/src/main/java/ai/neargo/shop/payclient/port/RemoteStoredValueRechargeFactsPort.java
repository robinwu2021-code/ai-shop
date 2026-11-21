package ai.neargo.shop.payclient.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteStoredValueRechargeFactsPort implements StoredValueRechargeFactsPort {
    private final PayInternalApi pay;
    public RemoteStoredValueRechargeFactsPort(PayInternalApi pay) { this.pay = pay; }
    @Override public RechargeFact query(EntitlementContext ctx, String entity, String no) {
        var f = RemoteEntitlementCalls.call(() -> pay.svFacts(new PayStoredValueRequests.Query(ctx, entity, no)));
        if (!ctx.tenantNo().equals(f.tenantNo()) || f.recharge() == null
                || !entity.equals(f.recharge().entityNo()) || !no.equals(f.recharge().rechargeNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return f;
    }
}
