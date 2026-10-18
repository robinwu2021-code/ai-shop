package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.policy.DefaultEarnPolicy;
import ai.neargo.shop.pay.points.policy.EarnPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cumulative goods ratios are independent of freight and preserve the original post-refund grant basis. */
class RefundRatioPolicyTest {
    @Test void late_grant_after_partial_refund_uses_remaining_basis() {
        var grant=new DefaultEarnPolicy().calculate(List.of(
                new EarnPolicy.Line("I",1,100,50,EarnPolicy.Mode.FIXED,100)),new RedeemRate(1,1)).lines().getFirst();
        assertThat(grant.grantedPoints()).isEqualTo(50);
        assertThat(reclaim(grant,50,0)).isZero();
        assertThat(reclaim(grant,75,0)).isEqualTo(25);
        assertThat(reclaim(grant,100,25)).isEqualTo(25);
    }
    @Test void final_goods_refund_returns_the_exact_aligned_tail() {
        assertThat(redemption(new RedeemRate(3,2),21,100,99,0)).isEqualTo(18);
        assertThat(redemption(new RedeemRate(3,2),21,100,100,18)).isEqualTo(3);
    }
    @Test void freight_only_and_zero_rule_lines_do_not_reclaim_goods_points() {
        assertThat(redemption(new RedeemRate(1,1),30,100,0,0)).isZero();
        var zero=new EarnPolicy.LineDecision("ZERO",0,0,0,0,0);
        assertThat(reclaim(zero,0,0)).isZero();
    }
    @Test void missing_baseline_and_regressing_processed_target_are_rejected() {
        assertThatThrownBy(() -> redemption(new RedeemRate(1,1),30,0,0,0))
                .hasMessage("POINTS_REFUND_BASELINE_INVALID");
        assertThatThrownBy(() -> redemption(new RedeemRate(1,1),30,100,10,20))
                .hasMessage("POINTS_REFUND_TARGET_REGRESSION");
    }
    private long reclaim(EarnPolicy.LineDecision grant,long cumulative,long processed) {
        return invoke("reclaimDelta",new RedeemRate(1,1),grant.grantedPoints(),grant.originalGoodsMinor(),
                grant.refundedBeforeGrantMinor(),grant.grantBasisMinor(),cumulative,processed);
    }
    private long redemption(RedeemRate rate,long points,long goods,long cumulative,long processed) {
        return invoke("redemptionDelta",rate,points,goods,cumulative,processed);
    }
    private long invoke(String name,RedeemRate rate,long... amounts) {
        try {
            Class<?> type=Class.forName("ai.neargo.shop.pay.points.policy.RefundRatioPolicy");
            Class<?>[] signature=new Class<?>[amounts.length+1]; signature[0]=RedeemRate.class;
            Object[] arguments=new Object[signature.length]; arguments[0]=rate;
            for (int i=0;i<amounts.length;i++) { signature[i+1]=long.class; arguments[i+1]=amounts[i]; }
            return (Long)type.getMethod(name,signature).invoke(type.getConstructor().newInstance(),arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Refund ratio policy missing",e); }
    }
}
