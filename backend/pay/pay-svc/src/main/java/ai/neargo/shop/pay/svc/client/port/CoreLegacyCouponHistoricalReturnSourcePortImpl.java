package ai.neargo.shop.pay.svc.client.port;
import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
public class CoreLegacyCouponHistoricalReturnSourcePortImpl implements LegacyCouponHistoricalReturnSourcePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreLegacyCouponHistoricalReturnSourcePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) {this.api=api;}
    public Fact resolve(EntitlementContext ctx,String no) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().historicalReturnSource(new EntitlementCoreInternal.HistoricalReturnQuery(ctx,no)));
    }
    public void acknowledge(EntitlementContext ctx,LegacyCouponHistoricalReturnPort.Receipt receipt) {
        var reply=EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().historicalReturnAck(new EntitlementCoreInternal.HistoricalReturnAck(ctx,receipt)));
        if(!receipt.equals(reply)) throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT);
    }
}
