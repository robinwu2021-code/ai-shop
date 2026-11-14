package ai.neargo.shop.pay.numbering;

import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Replay;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;



/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueNumberedPlanRechargeService extends StoredValuePlanRechargeService {
    RechargeView create(EntitlementContext context,String entity,Create command);

    RechargeView confirmOffline(EntitlementContext context,String entity,Create command);

    RechargeView query(EntitlementContext c,String entity,String rechargeNo);

    ReplayResult replay(EntitlementContext c,String entity,Replay command);

    RechargeView cancelCreation(EntitlementContext c,String entity,Replay command);

    void bindCustomer(EntitlementContext c,String customer,String user);

    boolean recover(EntitlementContext c,String entity,String rechargeNo);
}
