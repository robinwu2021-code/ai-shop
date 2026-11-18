package ai.neargo.shop.paybridge.impl;

import static ai.neargo.shop.paybridge.policy.StoredValueRechargeFactsPolicy.requireMatches;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.StoredValueRechargeCoordinator;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ConfirmParticipation;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReleaseParticipation;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** No cross-resource transaction. Every retry retains the original source, receipt decision and credit identity. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class StoredValueRechargeCoordinatorImpl implements StoredValueRechargeCoordinator {
    private final RechargeIntentService intents;
    private final StoredValueRechargeAppService pay;
    private final StoredValueRechargeFactsPort facts;
    private final ActivityParticipationPort marketing;
    public StoredValueRechargeCoordinatorImpl(RechargeIntentService intents, StoredValueRechargeAppService pay,
            StoredValueRechargeFactsPort facts, ActivityParticipationPort marketing) {
        this.intents = intents; this.pay = pay; this.facts = facts; this.marketing = marketing;
    }
    @Override public StoredValueRechargeProgress create(EntitlementContext ctx, String entity, RechargeIntentCreate c,
                                                        String key) {
        var p = intents.begin(ctx, entity, c, key); return recover(ctx, entity, p.rechargeNo());
    }
    @Override public StoredValueRechargeProgress submit(EntitlementContext ctx, String entity, SubmitRecharge c) {
        owned(ctx, entity, c.rechargeNo()); pay.submit(ctx, entity, c); return query(ctx, entity, c.rechargeNo());
    }
    @Override public StoredValueRechargeProgress receive(EntitlementContext ctx, String entity, ReceiveRecharge c) {
        owned(ctx, entity, c.rechargeNo()); pay.receive(ctx, entity, c);
        // The durable Received event performs posting; a merchant need not repeat confirmation while credit is pending.
        return query(ctx, entity, c.rechargeNo());
    }
    @Override public StoredValueRechargeProgress cancel(EntitlementContext ctx, String entity, CancelRecharge c) {
        owned(ctx, entity, c.rechargeNo()); pay.cancel(ctx, entity, c); return recover(ctx, entity, c.rechargeNo());
    }
    @Override public StoredValueRechargeProgress query(EntitlementContext ctx, String entity, String no) {
        var p = owned(ctx, entity, no); var f = requireMatches(p, facts.query(ctx, entity, no));
        return f.recharge().status() == RechargeState.NOT_FOUND ? StoredValueRechargeProgress.pending(p)
                : StoredValueRechargeProgress.from(f.recharge());
    }
    @Override public StoredValueRechargeProgress recover(EntitlementContext ctx, String entity, String no) {
        var p = owned(ctx, entity, no);
        var f = requireMatches(p, facts.query(p.context(), entity, no));
        if (f.recharge().status() == RechargeState.NOT_FOUND) {
            pay.create(p.context(), entity, new CreateRecharge(p.source(), p.permit()));
            f = requireMatches(p, facts.query(p.context(), entity, no));
        }
        if (f.recharge().status() == RechargeState.RECEIVED) {
            var source = new SourceRef("PAY", "RECHARGE", no, null, null, null, 0);
            pay.credit(p.context(), new CreditRecharge(no, source, f.receivedDecision()));
            f = requireMatches(p, facts.query(p.context(), entity, no));
        }
        if (f.recharge().status() == RechargeState.CREDITED) {
            marketing.confirm(p.context(), new ConfirmParticipation(p.permit().reservationNo(), p.source(), no));
            intents.markSettled(p.context(), p.intentNo());
        } else if (f.recharge().status() == RechargeState.CANCELLED) {
            marketing.release(p.context(), new ReleaseParticipation(p.permit().reservationNo(), p.source(), no));
            intents.markSettled(p.context(), p.intentNo());
        }
        return f.recharge().status() == RechargeState.NOT_FOUND ? StoredValueRechargeProgress.pending(p)
                : StoredValueRechargeProgress.from(f.recharge());
    }
    private RechargeIntentPlan owned(EntitlementContext ctx, String entity, String no) {
        var p = intents.byRechargeNo(ctx.tenantNo(), no);
        if (!p.permit().entityNo().equals(entity)) throw BizException.of(ErrorCode.NOT_FOUND);
        return p;
    }
}
