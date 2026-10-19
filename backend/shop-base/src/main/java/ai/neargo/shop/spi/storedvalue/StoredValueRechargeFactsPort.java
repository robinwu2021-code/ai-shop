package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;

/** Pay-owned facts for recovery and marketing settlement. No private receipt or pool projection. */
public interface StoredValueRechargeFactsPort {
    RechargeFact query(EntitlementContext context, String entityNo, String rechargeNo);
    record RechargeFact(String tenantNo, RechargeView recharge, SourceRef source, ParticipationPermit permit,
                        String operatorNo, DecisionRef receivedDecision) { }
}
