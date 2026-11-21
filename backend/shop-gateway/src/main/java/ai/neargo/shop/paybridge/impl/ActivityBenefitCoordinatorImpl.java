package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.dto.BenefitGrantPlan;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantView;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.State;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantDecision;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.AbortGrant;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ConfirmParticipation;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReleaseParticipation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent owner transactions; uncertainty retains every original command and every marketing reservation. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ActivityBenefitCoordinatorImpl implements ActivityBenefitCoordinator {
    private final ActivityBenefitIntentService intents;
    private final EntitlementGrantPort pay;
    private final ActivityParticipationPort participation;
    public ActivityBenefitCoordinatorImpl(ActivityBenefitIntentService intents, EntitlementGrantPort pay,
                                           ActivityParticipationPort participation) {
        this.intents = intents; this.pay = pay; this.participation = participation;
    }
    @Override public ClaimView claim(EntitlementContext ctx, BenefitClaimCommand command, String key) {
        return run(intents.begin(ctx, command, key));
    }
    @Override public ClaimView recover(EntitlementContext ctx, String no) { return run(intents.mine(ctx, no)); }
    @Override public ClaimView query(EntitlementContext ctx, String no) {
        var plan = intents.mine(ctx, no);
        if (!plan.status().equals("COMMITTED") && !plan.status().equals("ABORTED")) return processing(plan);
        var results = plan.grants().stream()
                .map(c -> pay.query(plan.context(), c.entityNo(), c.grantNo())).toList();
        var expected = plan.status().equals("COMMITTED") ? State.COMMITTED : State.ABORTED;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).status() != expected
                    || !plan.grants().get(i).sourceRef().equals(results.get(i).sourceRef())) return processing(plan);
        }
        return new ClaimView(plan.intentNo(), plan.status(), results);
    }
    private ClaimView run(BenefitGrantPlan plan) {
        if (plan.status().equals("PENDING")) {
            try {
                for (var child : plan.grants()) {
                    var hold = pay.prepare(plan.context(), child);
                    if (hold.status() == State.ABORTED) {
                        plan = intents.decide(plan.context(), plan.intentNo(), false); break;
                    }
                    if (hold.status() != State.PREPARED && hold.status() != State.COMMITTED) return processing(plan);
                }
                if (plan.status().equals("PENDING")) plan = intents.decide(plan.context(), plan.intentNo(), true);
            } catch (BizException failure) {
                if (!definitive(failure)) return processing(plan);
                plan = intents.decide(plan.context(), plan.intentNo(), false);
            }
        }
        boolean commit = plan.status().equals("COMMIT_DECIDED") || plan.status().equals("COMMITTED");
        var results = new ArrayList<GrantView>();
        try {
            for (var child : plan.grants()) {
                var result = commit ? pay.commit(plan.context(), new GrantDecision(child.grantNo(), child.sourceRef(),
                        plan.decision(child.sourceRef()))) : pay.abort(plan.context(), new AbortGrant(child.grantNo(),
                        child.sourceRef(), plan.decision(child.sourceRef())));
                results.add(result);
                if (result.status() != (commit ? State.COMMITTED : State.ABORTED)) return processing(plan);
            }
            if (plan.permit() != null) {
                String fact = plan.grants().getFirst().grantNo();
                if (commit) participation.confirm(plan.context(), new ConfirmParticipation(
                        plan.permit().reservationNo(), plan.parentSource(), fact));
                else participation.release(plan.context(), new ReleaseParticipation(
                        plan.permit().reservationNo(), plan.parentSource(), fact));
            }
            plan = intents.finish(plan.context(), plan.intentNo(), commit);
            return new ClaimView(plan.intentNo(), plan.status(), results);
        } catch (BizException failure) {
            // The original durable decision wins, even if a remote conflict requires operator investigation.
            return processing(plan);
        }
    }
    private ClaimView processing(BenefitGrantPlan plan) {
        return new ClaimView(plan.intentNo(), "PROCESSING", List.of());
    }
    private static boolean definitive(BizException failure) {
        return switch (failure.errorCode()) {
            case BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT -> true;
            default -> false;
        };
    }
}
