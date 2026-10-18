package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.policy.EarnPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EarnPolicyTest {
    @Test
    void quantity_zero_cap_and_alignment() {
        var decision=policy().calculate(List.of(new EarnPolicy.Line("I1",3,100,0,EarnPolicy.Mode.FIXED,50),
                new EarnPolicy.Line("GIFT",1,0,0,EarnPolicy.Mode.FIXED,999),
                new EarnPolicy.Line("ZERO",1,100,0,EarnPolicy.Mode.FIXED,0)),new RedeemRate(1,1));
        assertThat(decision.lines().getFirst().originalRulePoints()).isEqualTo(150L);
        assertThat(decision.grantedPoints()).isEqualTo(100L);
        assertThat(decision.lines().get(1).grantedPoints()).isZero();
        assertThat(decision.lines().get(2).grantedPoints()).isZero();
    }
    @Test
    void late_grant_uses_remaining_goods_cash_and_records_the_refund_baseline() {
        var line=policy().calculate(List.of(new EarnPolicy.Line("I",1,100,50,EarnPolicy.Mode.FIXED,100)),
                new RedeemRate(1,1)).lines().getFirst();
        assertThat(line.grantedPoints()).isEqualTo(50L);
        assertThat(line.refundedBeforeGrantMinor()).isEqualTo(50L);
        assertThat(line.grantBasisMinor()).isEqualTo(50L);
    }
    @Test
    void ratio_and_fixed_results_floor_to_the_redemption_unit() {
        var decision=policy().calculate(List.of(new EarnPolicy.Line("I",1,100,0,EarnPolicy.Mode.RATIO,1000)),
                new RedeemRate(3,2));
        assertThat(decision.grantedPoints()).isEqualTo(15L);
    }
    private EarnPolicy policy() {
        try {
            return (EarnPolicy)Class.forName("ai.neargo.shop.pay.points.policy.DefaultEarnPolicy")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException e) { throw new AssertionError("Integer earning policy missing",e); }
    }
}
