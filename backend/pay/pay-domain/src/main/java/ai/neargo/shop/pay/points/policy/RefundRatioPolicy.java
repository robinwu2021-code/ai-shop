package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;

import java.math.BigInteger;

/** Cumulative goods ratios avoid per-refund rounding drift and never count freight as refundable goods. */
public final class RefundRatioPolicy {
    /** Returns only the newly due redemption points; the final goods refund releases the aligned remainder. */
    public long redemptionDelta(RedeemRate rate,long originalPoints,long originalGoodsMinor,
                                long cumulativeGoodsMinor,long processedPoints) {
        validate(rate,originalPoints,originalGoodsMinor,cumulativeGoodsMinor,processedPoints);
        if (originalPoints==0) { return 0; }
        require(originalGoodsMinor>0,"POINTS_REFUND_BASELINE_INVALID");
        long target=cumulativeGoodsMinor==originalGoodsMinor?originalPoints:
                proportion(rate,originalPoints,cumulativeGoodsMinor,originalGoodsMinor);
        return delta(target,processedPoints);
    }

    /** Uses the remaining basis recorded when granting, rather than prorating an already reduced grant again. */
    public long reclaimDelta(RedeemRate rate,long grantedPoints,long originalGoodsMinor,long refundedBeforeGrantMinor,
                             long grantBasisMinor,long cumulativeGoodsMinor,long processedPoints) {
        validate(rate,grantedPoints,originalGoodsMinor,cumulativeGoodsMinor,processedPoints);
        require(refundedBeforeGrantMinor>=0 && refundedBeforeGrantMinor<=originalGoodsMinor
                && grantBasisMinor==originalGoodsMinor-refundedBeforeGrantMinor,"POINTS_REFUND_BASELINE_INVALID");
        if (grantedPoints==0) { return 0; }
        require(grantBasisMinor>0,"POINTS_REFUND_BASELINE_INVALID");
        long afterGrant=Math.max(0,cumulativeGoodsMinor-refundedBeforeGrantMinor);
        long target=cumulativeGoodsMinor==originalGoodsMinor?grantedPoints:
                proportion(rate,grantedPoints,afterGrant,grantBasisMinor);
        return delta(target,processedPoints);
    }
    private void validate(RedeemRate rate,long points,long goods,long refunded,long processed) {
        require(rate!=null && points>=0 && goods>=0 && refunded>=0 && refunded<=goods
                && processed>=0 && processed<=points,"POINTS_REFUND_INPUT_INVALID");
        rate.requireAligned(points); rate.requireAligned(processed);
    }
    private long proportion(RedeemRate rate,long points,long numerator,long denominator) {
        return rate.floorAligned(BigInteger.valueOf(points).multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator)).longValueExact());
    }
    private long delta(long target,long processed) {
        require(target>=processed,"POINTS_REFUND_TARGET_REGRESSION");
        return target-processed;
    }
    private void require(boolean condition,String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
