package ai.neargo.shop.paybridge.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort.RechargeFact;
import java.util.List;

/** Bind recovery to the original business facts; an empty or unrelated owner reply can never settle marketing. */
public final class StoredValueRechargeFactsPolicy {
    private StoredValueRechargeFactsPolicy() { }
    public static RechargeFact requireMatches(RechargeIntentPlan p, RechargeFact f) {
        if (f == null || f.recharge() == null || f.recharge().status() == null) unavailable();
        var r = f.recharge();
        if (!p.context().tenantNo().equals(f.tenantNo()) || !p.rechargeNo().equals(r.rechargeNo())
                || !p.permit().entityNo().equals(r.entityNo())) conflict();
        if (r.status() == RechargeState.NOT_FOUND) {
            if (f.source() != null || f.permit() != null || f.receivedDecision() != null || f.operatorNo() != null
                    || r.receivedAt() != null || r.creditedAt() != null || r.userNo() != null) conflict();
            return f;
        }
        if (!p.source().equals(f.source()) || !p.permit().equals(f.permit())
                || !p.context().actorNo().equals(f.operatorNo()) || !p.permit().userNo().equals(r.userNo())
                || !p.permit().storeNo().equals(r.storeNo()) || !p.permit().currency().equals(r.currency())
                || !p.permit().payAmountMinor().equals(r.paidAmountMinor()) || r.creditedTotalMinor() == null
                || r.creditedTotalMinor() != Math.addExact(p.permit().payAmountMinor(),
                        p.permit().bonusAmountMinor())) {
            conflict();
        }
        if (List.of(RechargeState.RECEIVED, RechargeState.CREDITED).contains(r.status())) {
            if (f.receivedDecision() == null || r.receivedAt() == null
                    || !r.receivedAt().equals(f.receivedDecision().occurredAt())) conflict();
            f.receivedDecision().requireFor(new SourceRef("PAY", "RECHARGE", p.rechargeNo(), null, null, null, 0),
                    DecisionRef.DecisionType.RECHARGE_RECEIVED);
        } else if (f.receivedDecision() != null || r.receivedAt() != null) conflict();
        if ((r.status() == RechargeState.CREDITED) != (r.creditedAt() != null)) conflict();
        return f;
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
