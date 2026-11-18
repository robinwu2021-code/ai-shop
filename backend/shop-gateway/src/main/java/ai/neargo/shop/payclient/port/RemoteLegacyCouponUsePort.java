package ai.neargo.shop.payclient.port;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayLegacyCouponRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteLegacyCouponUsePort implements LegacyCouponUsePort {
    private final PayInternalApi pay;
    public RemoteLegacyCouponUsePort(PayInternalApi pay){this.pay=pay;}
    public Snapshot snapshot(EntitlementContext ctx,Owned c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponSnapshot(new PayLegacyCouponRequests.SnapshotRequest(ctx,c)));}
    public Estimate estimate(EntitlementContext ctx,EstimateCommand c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponEstimate(new PayLegacyCouponRequests.EstimateRequest(ctx,c)));}
    public Quote quote(EntitlementContext ctx,Prepare c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponQuote(new PayLegacyCouponRequests.PrepareRequest(ctx,c)));}
    public Operation prepare(EntitlementContext ctx,Prepare c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponPrepare(new PayLegacyCouponRequests.PrepareRequest(ctx,c)));}
    public Operation commit(EntitlementContext ctx,Complete c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponCommit(new PayLegacyCouponRequests.CompleteRequest(ctx,c)));}
    public Operation release(EntitlementContext ctx,Complete c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponRelease(new PayLegacyCouponRequests.CompleteRequest(ctx,c)));}
    public Operation reverse(EntitlementContext ctx,Complete c){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponReverse(new PayLegacyCouponRequests.CompleteRequest(ctx,c)));}
    public Operation query(EntitlementContext ctx,String entity,String no){
        return RemoteEntitlementCalls.call(()->pay.legacyCouponQuery(new PayLegacyCouponRequests.Query(ctx,entity,no)));}
}
