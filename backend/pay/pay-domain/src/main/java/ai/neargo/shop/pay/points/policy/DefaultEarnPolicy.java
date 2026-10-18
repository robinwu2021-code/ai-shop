package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** FIXED uses quantity, RATIO uses basis points of cash goods; free items never mint value. */
public final class DefaultEarnPolicy implements EarnPolicy {
    @Override
    public EarnDecision calculate(List<Line> lines, RedeemRate rate) {
        List<LineDecision> decisions=new ArrayList<>();
        long expected=0; long actual=0;
        for (Line line:lines) {
            if (line.quantity()<0 || line.goodsCashMinor()<0 || line.refundedBeforeGrantMinor()<0
                    || line.refundedBeforeGrantMinor()>line.goodsCashMinor() || line.value()<0 || line.mode()==null) {
                throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
            }
            long raw=0;
            if (line.quantity()>0 && line.goodsCashMinor()>0) {
                if (line.mode()==Mode.FIXED) {
                    raw=BigInteger.valueOf(line.value()).multiply(BigInteger.valueOf(line.quantity())).longValueExact();
                } else {
                    long money=BigInteger.valueOf(line.goodsCashMinor()).multiply(BigInteger.valueOf(line.value()))
                            .divide(BigInteger.valueOf(10000)).longValueExact();
                    raw=rate.pointsForMinor(money);
                }
            }
            long capped=rate.floorAligned(Math.min(raw,rate.pointsForMinor(line.goodsCashMinor())));
            long basis=line.goodsCashMinor()-line.refundedBeforeGrantMinor();
            long earned=line.goodsCashMinor()==0?0:rate.floorAligned(BigInteger.valueOf(capped)
                    .multiply(BigInteger.valueOf(basis)).divide(BigInteger.valueOf(line.goodsCashMinor())).longValueExact());
            expected=Math.addExact(expected,capped); actual=Math.addExact(actual,earned);
            decisions.add(new LineDecision(line.itemId(),raw,earned,line.goodsCashMinor(),
                    line.refundedBeforeGrantMinor(),basis));
        }
        return new EarnDecision(expected,actual,decisions);
    }
}
