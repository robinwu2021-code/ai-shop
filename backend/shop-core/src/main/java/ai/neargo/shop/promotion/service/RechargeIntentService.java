package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;

public interface RechargeIntentService {
    RechargeIntentPlan begin(EntitlementContext context, String entityNo, RechargeIntentCreate command, String key);
    RechargeIntentPlan find(String tenantNo, String intentNo);
    RechargeIntentPlan byRechargeNo(String tenantNo, String rechargeNo);
    void validateParticipation(EntitlementContext context, ReserveActivity command);
    void requireSource(RechargeIntentPlan plan, SourceRef source);
}
