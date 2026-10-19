package ai.neargo.shop.pay.points;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integer conversions must preserve indivisible redemption units and reject overflow. */
class RedeemRateTest {
    @Test
    void aligned_signed_changes_use_exact_integer_money() {
        Object rate = rate(3L, 2L);
        assertThat(call(rate, "toMinor", 150L)).isEqualTo(100L);
        assertThat(call(rate, "toMinor", -150L)).isEqualTo(-100L);
        assertThat(call(rate, "floorAligned", 152L)).isEqualTo(150L);
    }

    @Test
    void unaligned_points_and_overflow_are_rejected() {
        assertThatThrownBy(() -> call(rate(3L, 2L), "toMinor", 4L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> call(rate(1L, 2L), "toMinor", Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void reduces_ratio_and_floors_money_without_double() {
        Object rate = rate(6L, 4L);
        assertThat(call(rate, "pointsForMinor", 5L)).isEqualTo(6L);
        assertThat(call(rate, "pointsForMinor", 0L)).isZero();
    }

    private Object rate(long points, long minor) {
        try {
            return Class.forName("ai.neargo.shop.pay.points.model.RedeemRate")
                    .getConstructor(long.class, long.class).newInstance(points, minor);
        } catch (InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Approved integer rate model is missing", e);
        }
    }

    private long call(Object rate, String method, long value) {
        try {
            return (long) rate.getClass().getMethod(method, long.class).invoke(rate, value);
        } catch (InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
