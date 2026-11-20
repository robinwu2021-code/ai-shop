package ai.neargo.shop.payclient.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A bound, present owner response is required before allowing any order repricing. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemotePaymentAttemptFactsPort implements PaymentAttemptFactsPort {
    private final PayInternalApi pay;
    public RemotePaymentAttemptFactsPort(PayInternalApi pay) { this.pay = pay; }
    @Override public boolean hasAnyAttempt(String tenant, String orderNo) {
        var ctx = new EntitlementContext(tenant, "SYSTEM", UUID.randomUUID().toString(), "0".repeat(64));
        var result = RemoteEntitlementCalls.call(() -> pay.entPaymentAttempts(
                new PayEntitlementRequests.PaymentAttempts(ctx, orderNo)));
        if (!tenant.equals(result.tenantNo()) || !orderNo.equals(result.orderNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return result.hasAnyAttempt();
    }
}
