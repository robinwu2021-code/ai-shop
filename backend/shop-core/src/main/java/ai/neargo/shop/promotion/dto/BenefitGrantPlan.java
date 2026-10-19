package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import java.time.Instant;
import java.util.List;

/** Core owns the frozen source and terminal decision; no pay balance or instance is copied here. */
public record BenefitGrantPlan(String intentNo, EntitlementContext context, BenefitClaimCommand request,
        SourceRef parentSource, ReserveActivity participation, ParticipationPermit permit, String currency,
        List<GrantCommand> grants, String status, Instant createdAt) {
    public BenefitGrantPlan { grants = List.copyOf(grants); }
    public DecisionRef decision(SourceRef source) {
        boolean abort = status.equals("ABORT_DECIDED") || status.equals("ABORTED");
        if (!abort && !status.equals("COMMIT_DECIDED") && !status.equals("COMMITTED")) return null;
        return new DecisionRef("BD" + source.grantKey().substring(0, 60), createdAt, source.sourceRevision(),
                abort ? DecisionRef.DecisionType.GRANT_ABORT : DecisionRef.DecisionType.GRANT_COMMIT);
    }
}
