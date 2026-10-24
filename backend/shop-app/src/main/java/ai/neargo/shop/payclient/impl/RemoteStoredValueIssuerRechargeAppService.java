package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteStoredValueIssuerRechargeAppService implements StoredValueIssuerRechargeAppService {
 private final PayInternalApi service;
 public RemoteStoredValueIssuerRechargeAppService(PayInternalApi service){this.service=service;}
 public CursorPage<FundedCustomer> funded(ExecutionScope s,String cursor,int size){return RemoteEntitlementCalls.call(()->service.svFundedCustomers(new ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.FundedPage(s,cursor,size)));}
 public StoreOptions issuers(ExecutionScope s){return RemoteEntitlementCalls.call(()->service.svExecutionIssuers(s));}
 public CursorPage<Option> options(Query q,String cursor,int size){return RemoteEntitlementCalls.call(()->service.svBrandRechargeOptions(new PayStoredValueIssuerRechargeRequests.Options(q,cursor,size)));}
 public RechargeView confirm(EntitlementContext c,String e,Create v){return RemoteEntitlementCalls.call(()->service.svBrandRechargeConfirm(new PayStoredValueIssuerRechargeRequests.Confirm(c,e,v)));}
 public RechargeView resume(EntitlementContext c,String e,Replay v){return RemoteEntitlementCalls.call(()->service.svBrandRechargeResume(new PayStoredValueIssuerRechargeRequests.Replay(c,e,v)));}
 public ReplayResult replay(EntitlementContext c,String e,Replay v){return RemoteEntitlementCalls.call(()->service.svBrandRechargeReplay(new PayStoredValueIssuerRechargeRequests.Replay(c,e,v)));}
 public RechargeView cancelCreation(EntitlementContext c,String e,Replay v){return RemoteEntitlementCalls.call(()->service.svBrandRechargeCancelCreate(new PayStoredValueIssuerRechargeRequests.Replay(c,e,v)));}
}
