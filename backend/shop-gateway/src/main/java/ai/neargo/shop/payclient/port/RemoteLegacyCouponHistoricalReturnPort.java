package ai.neargo.shop.payclient.port;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteLegacyCouponHistoricalReturnPort implements LegacyCouponHistoricalReturnPort {
    private final PayInternalApi pay;
    public RemoteLegacyCouponHistoricalReturnPort(PayInternalApi pay) {this.pay=pay;}
    public Receipt reverse(EntitlementContext ctx,String entity,String no) {
        return RemoteEntitlementCalls.call(()->pay.historicalReturnReverse(new PayHistoricalReturnRequests.Query(ctx,entity,no)));
    }
    public Receipt query(EntitlementContext ctx,String entity,String no) {
        return RemoteEntitlementCalls.call(()->pay.historicalReturnQuery(new PayHistoricalReturnRequests.Query(ctx,entity,no)));
    }
}
