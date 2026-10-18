package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;

import java.util.List;

/** Pure earning arithmetic, independent of framework, storage, and cross-domain SPI classes. */
public interface EarnPolicy {
    EarnDecision calculate(List<Line> lines, RedeemRate rate);
    enum Mode { FIXED, RATIO }
    record Line(String itemId, long quantity, long goodsCashMinor, long refundedBeforeGrantMinor,
                Mode mode, long value) { }
    record LineDecision(String itemId, long originalRulePoints, long grantedPoints,
                        long originalGoodsMinor, long refundedBeforeGrantMinor, long grantBasisMinor) { }
    record EarnDecision(long expectedPoints, long grantedPoints, List<LineDecision> lines) {
        public EarnDecision { lines=List.copyOf(lines); }
    }
}
