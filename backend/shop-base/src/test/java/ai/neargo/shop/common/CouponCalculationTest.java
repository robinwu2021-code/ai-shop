package ai.neargo.shop.common;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.common.promotion.CouponCalculation;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CouponCalculationTest {
    @Test
    void percentUsesPayableBasisPointsFloorsMinorUnitsAndCapsWithoutMultiplicationOverflow() {
        assertThat(CouponCalculation.discount("PERCENT", 8500L, null, 101)).isEqualTo(15);
        assertThat(CouponCalculation.discount("PERCENT", 8500L, 10L, 101)).isEqualTo(10);
        assertThat(CouponCalculation.discount("CASH", 150L, 10L, 101)).isEqualTo(101);
        assertThat(CouponCalculation.discount("PERCENT", 5000L, null, Long.MAX_VALUE))
                .isEqualTo(Long.MAX_VALUE / 2);
    }
    @Test
    void roundingRemainderGoesToLargestWithoutNegativeOrOverallocatedLines() {
        assertThat(CouponCalculation.allocate(5, List.of(10L, 20L))).containsExactly(2L, 3L);
        var small = CouponCalculation.allocate(50, Collections.nCopies(100, 1L));
        assertThat(small).allMatch(n -> n >= 0 && n <= 1);
        assertThat(small.stream().mapToLong(Long::longValue).sum()).isEqualTo(50);
        assertThat(CouponCalculation.allocate(Long.MAX_VALUE, List.of(Long.MAX_VALUE - 1, 1L)))
                .containsExactly(Long.MAX_VALUE - 1, 1L);
    }
}
