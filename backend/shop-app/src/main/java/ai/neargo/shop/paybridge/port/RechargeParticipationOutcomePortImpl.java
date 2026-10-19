package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationOutcomePort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import org.springframework.stereotype.Component;

@Component
public class RechargeParticipationOutcomePortImpl implements ActivityParticipationOutcomePort {
    private final RechargeIntentService intents;
    private final StoredValueRechargeFactsPort pay;
    public RechargeParticipationOutcomePortImpl(RechargeIntentService intents, StoredValueRechargeFactsPort pay) {
        this.intents = intents; this.pay = pay;
    }
    @Override public boolean supports(SourceRef s) {
        return "CORE".equals(s.sourceSystem()) && "RECHARGE".equals(s.sourceType())
                && s.sourceRevision() == 0 && s.sourceLineNo() == null && s.unitIndex() == null
                && s.entitlementIndex() == null;
    }
    @Override public void validateSource(EntitlementContext ctx, ReserveActivity c) {
        intents.validateParticipation(ctx, c);
    }
    @Override public Outcome outcome(EntitlementContext ctx, SourceRef s) {
        var p = intents.find(ctx.tenantNo(), s.sourceNo()); intents.requireSource(p, s);
        var f = pay.query(p.context(), p.participation().entityNo(), p.rechargeNo());
        var r = f.recharge();
        if (r.status() == RechargeState.NOT_FOUND) return new Outcome(State.UNKNOWN, null, ctx.tenantNo(),
                p.participation());
        if (!ctx.tenantNo().equals(f.tenantNo()) || !p.source().equals(f.source()) || !p.permit().equals(f.permit())
                || !p.context().actorNo().equals(f.operatorNo()) || !p.rechargeNo().equals(r.rechargeNo())
                || !p.permit().userNo().equals(r.userNo()) || !p.permit().entityNo().equals(r.entityNo())
                || !p.permit().storeNo().equals(r.storeNo()) || !p.permit().currency().equals(r.currency())
                || !p.permit().payAmountMinor().equals(r.paidAmountMinor())
                || r.creditedTotalMinor() == null || r.creditedTotalMinor() != Math.addExact(
                        p.permit().payAmountMinor(), p.permit().bonusAmountMinor())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        State state = r.status() == RechargeState.CREDITED ? State.COMMITTED
                : r.status() == RechargeState.CANCELLED ? State.ABORTED : State.UNKNOWN;
        return new Outcome(state, state == State.UNKNOWN ? null : r.rechargeNo(), ctx.tenantNo(), p.participation());
    }
}
