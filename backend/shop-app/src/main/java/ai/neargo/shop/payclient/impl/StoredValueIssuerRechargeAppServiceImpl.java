package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.payclient.StoredValueIssuerRechargeAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueIssuerRechargeAppServiceImpl implements StoredValueIssuerRechargeAppService {
 private final StoredValueBrandRechargeService service;private final StoredValueIssuerQueryService queries;
 public StoredValueIssuerRechargeAppServiceImpl(StoredValueBrandRechargeService service,StoredValueIssuerQueryService queries){this.service=service;this.queries=queries;}
 public CursorPage<FundedCustomer> funded(ExecutionScope s,String cursor,int size){return queries.funded(s,cursor,size);}
 public StoreOptions issuers(ExecutionScope s){return queries.storeOptions(s);}
 public CursorPage<Option> options(Query q,String cursor,int size){return service.options(q,cursor,size);}
 public RechargeView confirm(EntitlementContext c,String e,Create v){return service.confirm(c,e,v);}
 public RechargeView resume(EntitlementContext c,String e,Replay v){return service.resume(c,e,v);}
 public ReplayResult replay(EntitlementContext c,String e,Replay v){return service.replay(c,e,v);}
 public RechargeView cancelCreation(EntitlementContext c,String e,Replay v){return service.cancelCreation(c,e,v);}
}
