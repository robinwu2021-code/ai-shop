package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourcePriceMathTest {
    @Test void extendsMinorUnitsOnceWithHalfUpAndNeverBinaryFloatingPoint() {
        assertThat(SourcePriceMath.extend(new MoneyMinor("CNY", 101), new BigDecimal("1.5")).minor()).isEqualTo(152);
        assertThat(SourcePriceMath.extend(new MoneyMinor("CNY", 1), new BigDecimal("0.499999")).minor()).isZero();
        assertThat(SourcePriceMath.quantity(new BigDecimal("2.0000000"))).isEqualTo(new BigDecimal("2"));
        assertThat(SourcePriceMath.quantity(new BigDecimal("1000"))).isEqualTo(new BigDecimal("1000"));
    }
    @Test void topupCannotReducePackagePriceAndRequiresSameCurrency() {
        assertThat(SourcePriceMath.topup(new MoneyMinor("CNY", 100), new MoneyMinor("CNY", 200)).minor()).isZero();
        assertThat(SourcePriceMath.topup(new MoneyMinor("CNY", 300), new MoneyMinor("CNY", 200)).minor())
                .isEqualTo(100);
        assertThatThrownBy(() -> SourcePriceMath.topup(new MoneyMinor("CNY", 1), new MoneyMinor("USD", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidPrecisionNegativeMoneyAndUnsafeResults() {
        for (String invalid : List.of("0", "-1", "0.0000001", "100000000000000")) {
            assertThatThrownBy(() -> SourcePriceMath.quantity(new BigDecimal(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SourcePriceMath.extend(new MoneyMinor("CNY", -1), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourcePriceMath.extend(new MoneyMinor("CNY", 9007199254740991L),
                new BigDecimal("2"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void digestNormalizesObjectOrderAndDecimalScaleButPreservesArrayOrderAndValue() {
        var left = new LinkedHashMap<String, Object>(); left.put("b", new BigDecimal("2.00")); left.put("a", 1);
        assertThat(PricingSnapshotHash.of(left)).isEqualTo(PricingSnapshotHash.of(Map.of("a", 1, "b", 2)));
        assertThat(PricingSnapshotHash.of(List.of("A", "B"))).isNotEqualTo(PricingSnapshotHash.of(List.of("B", "A")));
        assertThat(PricingSnapshotHash.of(Map.of("price", 1))).isNotEqualTo(PricingSnapshotHash.of(Map.of("price", 2)));
    }
}
