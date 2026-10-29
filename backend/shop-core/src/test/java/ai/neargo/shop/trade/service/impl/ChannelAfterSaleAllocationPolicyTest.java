package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class ChannelAfterSaleAllocationPolicyTest {
    @Test
    void threeDecimalPartialReturnsConserveEveryOriginalMinorUnit() {
        BigDecimal original = new BigDecimal("1.25"), used = BigDecimal.ZERO;
        long minor = 0;
        for (String part : new String[] {"0.333333", "0.333333", "0.583334"}) {
            var selected = ChannelAfterSaleAllocationPolicy.quantity(part);
            long delta =
                    ChannelAfterSaleAllocationPolicy.incrementalCeiling(
                            101, original, used, minor, selected);
            used = used.add(selected);
            minor = Math.addExact(minor, delta);
        }
        assertThat(used).isEqualByComparingTo(original);
        assertThat(minor).isEqualTo(101);
    }

    @Test
    void originalQuantityAndMoneyCapsCannotBeReusedOrInflated() {
        assertThatThrownBy(
                        () ->
                                ChannelAfterSaleAllocationPolicy.incrementalCeiling(
                                        100,
                                        new BigDecimal("1.25"),
                                        new BigDecimal("1"),
                                        80,
                                        new BigDecimal("0.250001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ChannelAfterSaleAllocationPolicy.incrementalCeiling(
                                        100,
                                        new BigDecimal("1.25"),
                                        new BigDecimal("0.5"),
                                        0,
                                        new BigDecimal("0.5")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void physicalQuantityRequiresExactPositiveBoundedDecimal() {
        for (var invalid : new String[] {"0", "-1", "1e2", "01", "0.0000001", " 0.1 ", "NaN"})
            assertThatThrownBy(() -> ChannelAfterSaleAllocationPolicy.quantity(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(ChannelAfterSaleAllocationPolicy.quantity("0.000001"))
                .isEqualByComparingTo("0.000001");
    }
}
