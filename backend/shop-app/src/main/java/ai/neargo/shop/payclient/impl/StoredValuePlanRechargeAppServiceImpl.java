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
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValuePlanRechargeAppServiceImpl implements StoredValuePlanRechargeAppService {
    private final StoredValuePlanRechargeService direct;
    public StoredValuePlanRechargeAppServiceImpl(StoredValuePlanRechargeService direct) { this.direct=direct; }
    @Override public RechargeView create(EntitlementContext c, String e, Create command) { return direct.create(c,e,command); }
    @Override public RechargeView query(EntitlementContext c, String e, String no) { return direct.query(c,e,no); }
    @Override public boolean recover(EntitlementContext c, String e, String no) { return direct.recover(c,e,no); }
    @Override public ReplayResult replay(EntitlementContext c,String e,Replay command){return direct.replay(c,e,command);}
    @Override public RechargeView cancelCreation(EntitlementContext c,String e,Replay command){return direct.cancelCreation(c,e,command);}
}
