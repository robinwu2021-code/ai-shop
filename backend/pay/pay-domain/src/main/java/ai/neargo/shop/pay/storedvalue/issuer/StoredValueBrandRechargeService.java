package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Option;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Replay;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueBrandRechargeService {
    CursorPage<Option> options(Query q,String cursor,int size);

    RechargeView confirm(EntitlementContext c,String entity,Create command);

    RechargeView resume(EntitlementContext c,String entity,Replay replay);

    ReplayResult replay(EntitlementContext c,String entity,Replay replay);

    RechargeView cancelCreation(EntitlementContext c,String entity,Replay replay);

    RechargeView query(EntitlementContext c,String entity,String n);

    boolean recover(EntitlementContext c,String entity,String n);
}
