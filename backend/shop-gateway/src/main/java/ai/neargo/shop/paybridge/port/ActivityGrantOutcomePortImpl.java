package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationOutcomePort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import org.springframework.stereotype.Component;

/** Marketing settles only after every pay child has a matching durable terminal fact. */
@Component
public class ActivityGrantOutcomePortImpl implements ActivityParticipationOutcomePort {
    private final ActivityBenefitIntentService intents;
    private final EntitlementGrantPort pay;
    public ActivityGrantOutcomePortImpl(ActivityBenefitIntentService intents, EntitlementGrantPort pay) {
        this.intents = intents; this.pay = pay;
    }
    @Override public boolean supports(SourceRef source) {
        return "CORE".equals(source.sourceSystem()) && "ACTIVITY".equals(source.sourceType())
                && source.sourceLineNo() == null && source.unitIndex() == null && source.entitlementIndex() == null;
    }
    @Override public void validateSource(EntitlementContext ctx, ReserveActivity command) {
        intents.validateParticipation(ctx, command);
    }
    @Override public Outcome outcome(EntitlementContext ctx, SourceRef source) {
        var plan = intents.find(ctx.tenantNo(), source.sourceNo()); intents.requireParent(plan, source);
        boolean commit = plan.status().equals("COMMIT_DECIDED") || plan.status().equals("COMMITTED");
        boolean abort = plan.status().equals("ABORT_DECIDED") || plan.status().equals("ABORTED");
        if (!commit && !abort) return new Outcome(State.UNKNOWN, null, ctx.tenantNo(), plan.participation());
        for (var child : plan.grants()) {
            var fact = pay.query(plan.context(), child.entityNo(), child.grantNo());
            if (!child.sourceRef().equals(fact.sourceRef())
                    || fact.status() != (commit ? EntitlementGrantPort.State.COMMITTED
                    : EntitlementGrantPort.State.ABORTED)) {
                return new Outcome(State.UNKNOWN, null, ctx.tenantNo(), plan.participation());
            }
        }
        if (plan.participation() == null) throw BizException.of(ErrorCode.CONFLICT);
        return new Outcome(commit ? State.COMMITTED : State.ABORTED, plan.grants().getFirst().grantNo(),
                ctx.tenantNo(), plan.participation());
    }
}
