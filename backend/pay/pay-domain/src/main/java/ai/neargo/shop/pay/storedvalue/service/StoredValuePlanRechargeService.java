package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Replay;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;

public interface StoredValuePlanRechargeService {
    RechargeView create(EntitlementContext context, String entityNo, Create command);
    ReplayResult replay(EntitlementContext context,String entityNo,Replay command);
    RechargeView cancelCreation(EntitlementContext context,String entityNo,Replay command);
    RechargeView query(EntitlementContext context, String entityNo, String rechargeNo);
    /** True only for a persisted PLAN source, without consulting marketing. */
    boolean recover(EntitlementContext context, String entityNo, String rechargeNo);
}
