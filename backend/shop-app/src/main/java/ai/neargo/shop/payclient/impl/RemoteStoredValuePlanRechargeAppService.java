package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValuePlanRechargeAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValuePlanRechargeRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Replay;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteStoredValuePlanRechargeAppService implements StoredValuePlanRechargeAppService {
    private final PayInternalApi direct;
    public RemoteStoredValuePlanRechargeAppService(PayInternalApi direct) { this.direct=direct; }
    @Override public void bindCustomer(EntitlementContext c,String customer,String user){RemoteEntitlementCalls.call(()->direct.svCustomerBind(new PayStoredValuePlanRechargeRequests.Bind(c,customer,user)));}
    @Override public RechargeView confirmOffline(EntitlementContext c,String e,Create command){return RemoteEntitlementCalls.call(()->direct.svPlanRechargeConfirmOffline(new PayStoredValuePlanRechargeRequests.Create(c,e,command)));}
    @Override public RechargeView create(EntitlementContext c, String e, Create command) { return RemoteEntitlementCalls.call(()->direct.svPlanRechargeCreate(new PayStoredValuePlanRechargeRequests.Create(c,e,command))); }
    @Override public RechargeView query(EntitlementContext c, String e, String no) { return RemoteEntitlementCalls.call(()->direct.svPlanRechargeQuery(new PayStoredValuePlanRechargeRequests.Query(c,e,no))); }
    @Override public boolean recover(EntitlementContext c, String e, String no) { return RemoteEntitlementCalls.call(()->direct.svPlanRechargeRecover(new PayStoredValuePlanRechargeRequests.Query(c,e,no))); }
    @Override public ReplayResult replay(EntitlementContext c,String e,Replay command){return RemoteEntitlementCalls.call(()->direct.svPlanRechargeReplay(new PayStoredValuePlanRechargeRequests.Replay(c,e,command)));}
    @Override public RechargeView cancelCreation(EntitlementContext c,String e,Replay command){return RemoteEntitlementCalls.call(()->direct.svPlanRechargeCancelCreation(new PayStoredValuePlanRechargeRequests.Replay(c,e,command)));}
}
