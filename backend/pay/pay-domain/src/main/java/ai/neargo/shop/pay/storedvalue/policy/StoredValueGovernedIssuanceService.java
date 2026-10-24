package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlanVersion;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;

/** Checks and freezes the existing governed issuance evidence. */
public interface StoredValueGovernedIssuanceService {
    void beforeIssuer(EntitlementContext ctx, String entity, SourceFact source);

    void beforePlanIssuer(EntitlementContext ctx, String entity, String store, String planVersion, StoreScope scope, String rechargeNo);

    void freezePlan(EntitlementContext ctx, String entity, SvRechargePlanVersion version, SvRecharge original);

    void freeze(EntitlementContext ctx, String entity, SourceFact source, SvRecharge original);
}
