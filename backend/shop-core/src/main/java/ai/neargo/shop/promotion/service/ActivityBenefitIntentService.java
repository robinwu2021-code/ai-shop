package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.dto.BenefitGrantPlan;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;

/** Source ownership and decisions are durable core transactions, never distributed pay transactions. */
public interface ActivityBenefitIntentService {
    BenefitGrantPlan begin(EntitlementContext context, BenefitClaimCommand command, String requestKey);
    BenefitGrantPlan mine(EntitlementContext context, String intentNo);
    BenefitGrantPlan find(String tenantNo, String intentNo);
    BenefitGrantPlan decide(EntitlementContext context, String intentNo, boolean commit);
    BenefitGrantPlan finish(EntitlementContext context, String intentNo, boolean committed);
    void validateParticipation(EntitlementContext context, ReserveActivity command);
    void requireParent(BenefitGrantPlan plan, SourceRef source);
}
