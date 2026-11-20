package ai.neargo.shop.payclient;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions;
public interface StoredValueIssuerRechargeAppService {
 CursorPage<FundedCustomer> funded(ExecutionScope scope,String cursor,int size);
 StoreOptions issuers(ExecutionScope scope);
 CursorPage<Option> options(Query q,String cursor,int size);
 RechargeView confirm(EntitlementContext c,String entity,Create command);
 RechargeView resume(EntitlementContext c,String entity,Replay replay);
 ReplayResult replay(EntitlementContext c,String entity,Replay replay);
 RechargeView cancelCreation(EntitlementContext c,String entity,Replay replay);
}
