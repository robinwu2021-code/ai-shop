package ai.neargo.shop.promotion.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import org.springframework.stereotype.Component;

/** A source reference alone is not proof: only the complete persisted child command may be prepared. */
@Component
public class ActivityGrantSourcePortImpl implements EntitlementGrantSourcePort {
    private final ActivityBenefitIntentService intents;
    public ActivityGrantSourcePortImpl(ActivityBenefitIntentService intents) { this.intents = intents; }
    @Override public boolean supports(SourceRef ref) {
        return "CORE".equals(ref.sourceSystem()) && ("ACTIVITY".equals(ref.sourceType())
                || "CENTER".equals(ref.sourceType()));
    }
    @Override public SourceFact resolve(EntitlementContext ctx, GrantCommand command) {
        var plan = intents.find(ctx.tenantNo(), command.sourceRef().sourceNo());
        if (!supports(command.sourceRef()) || plan.grants().stream().noneMatch(command::equals)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new SourceFact(ctx.tenantNo(), command, null);
    }
    @Override public SourceDecisionPort.SourceDecision decision(EntitlementContext ctx, SourceRef ref) {
        var plan = intents.find(ctx.tenantNo(), ref.sourceNo());
        var command = plan.grants().stream().filter(c -> c.sourceRef().equals(ref)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
        var decision = plan.decision(ref);
        var state = decision == null ? SourceDecisionPort.State.PENDING
                : decision.decisionType() == DecisionRef.DecisionType.GRANT_COMMIT
                ? SourceDecisionPort.State.COMMITTED : SourceDecisionPort.State.CANCELLED;
        return new SourceDecisionPort.SourceDecision(ref, state, decision, command.recipientUserNo(),
                command.entityNo(), plan.request().storeNo(), plan.currency(), 0L, command.benefitSnapshotHash());
    }
}
