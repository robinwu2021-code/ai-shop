package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.model.RedeemRate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;

class CashClawbackPolicyTest {
    @Test void offset_is_bounded_aligned_and_never_makes_a_negative_refund() {
        Object plan=allocate(new RedeemRate(3,2),150,71,true);
        assertThat(amount(plan,"cashOffsetMinor")).isEqualTo(70);
        assertThat(amount(plan,"netCashMinor")).isEqualTo(1);
        assertThat(amount(plan,"waivedPoints")).isEqualTo(45);
    }
    @Test void unsupported_collection_keeps_full_cash_refund_and_records_platform_cost() {
        Object plan=allocate(new RedeemRate(1,1),100,80,false);
        assertThat(amount(plan,"cashOffsetMinor")).isZero();
        assertThat(amount(plan,"netCashMinor")).isEqualTo(80);
        assertThat(amount(plan,"waivedPoints")).isEqualTo(100);
    }
    private Object allocate(RedeemRate rate,long points,long cash,boolean supported) {
        try {
            var type=Class.forName("ai.neargo.shop.pay.points.policy.CashClawbackPolicy");
            return type.getMethod("allocate",RedeemRate.class,long.class,long.class,boolean.class)
                    .invoke(type.getConstructor().newInstance(),rate,points,cash,supported);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Cash clawback policy missing",e); }
    }
    private long amount(Object plan,String name) {
        try { return (Long)plan.getClass().getMethod(name).invoke(plan); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
