package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;
import java.math.BigInteger;
import java.util.*;

/** A delayed grant must use confirmed cumulative refunds and the original paid goods baseline. */
public final class DelayedEarnPolicy {
    private DelayedEarnPolicy() {}
    public static EarnPolicy.EarnDecision apply(EarnPolicy.EarnDecision original, Map<String,Long> goods,
                                                Map<String,Long> refunded, RedeemRate rate) {
        var lines=new ArrayList<EarnPolicy.LineDecision>();long granted=0;
        for(var line:original.lines()) {
            Long baseline=goods.get(line.itemId());long returned=refunded.getOrDefault(line.itemId(),0L);
            if(baseline==null||baseline<0||returned<0||returned>baseline)
                throw new IllegalArgumentException("POINTS_EARN_REFUND_BASELINE_INVALID");
            long earned=baseline==0?0:rate.floorAligned(BigInteger.valueOf(line.grantedPoints())
                    .multiply(BigInteger.valueOf(baseline-returned)).divide(BigInteger.valueOf(baseline)).longValueExact());
            granted=Math.addExact(granted,earned);
            lines.add(new EarnPolicy.LineDecision(line.itemId(),line.originalRulePoints(),earned,baseline,returned,baseline-returned));
        }
        if(goods.size()!=lines.size()||!goods.keySet().containsAll(refunded.keySet()))
            throw new IllegalArgumentException("POINTS_EARN_REFUND_BASELINE_INVALID");
        return new EarnPolicy.EarnDecision(original.expectedPoints(),granted,List.copyOf(lines));
    }
}
