package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;

/** Only a path that can return real collection cash to the platform may reduce the buyer's refund. */
public final class CashClawbackPolicy {
    /** Produces an aligned offset; this decision is not a receipt and never credits issuance U. */
    public Plan allocate(RedeemRate rate,long missingPoints,long cashRefundMinor,boolean collectionSupported) {
        if (rate==null || missingPoints<0 || cashRefundMinor<0) {
            throw new IllegalArgumentException("POINTS_CLAWBACK_INPUT_INVALID");
        }
        rate.requireAligned(missingPoints);
        long cash=collectionSupported?Math.min(rate.toMinor(missingPoints),cashRefundMinor):0;
        cash-=cash%rate.minorUnit();
        return new Plan(cash,cashRefundMinor-cash,missingPoints-rate.pointsForMinor(cash));
    }
    public record Plan(long cashOffsetMinor,long netCashMinor,long waivedPoints) { }
}
