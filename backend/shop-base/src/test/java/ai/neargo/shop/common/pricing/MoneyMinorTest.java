package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyMinorTest {
    private static final long JSON_LIMIT = 9_007_199_254_740_991L;

    @Test
    void rejectOverflowAndCurrencyMismatch() {
        assertThatThrownBy(() -> new MoneyMinor("CNY", Long.MAX_VALUE).add(new MoneyMinor("CNY", 1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new MoneyMinor("CNY", Long.MIN_VALUE).subtract(new MoneyMinor("CNY", 1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new MoneyMinor("CNY", 100).add(new MoneyMinor("AED", 200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoneyMinor("CNY", 0).subtract(new MoneyMinor("AED", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signedDifferencesRemainExactWithoutChangingOperands() {
        MoneyMinor before = new MoneyMinor("AED", 100);
        assertThat(before.add(new MoneyMinor("AED", 200))).isEqualTo(new MoneyMinor("AED", 300));
        assertThat(before.subtract(new MoneyMinor("AED", 200))).isEqualTo(new MoneyMinor("AED", -100));
        assertThat(before).isEqualTo(new MoneyMinor("AED", 100));
    }

    @Test
    void requiresExplicitCanonicalIsoCurrency() {
        for (String currency : new String[] {null, "", "cny", " CNY", "CNY ", "CN", "ZZZ"}) {
            assertThatThrownBy(() -> new MoneyMinor(currency, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void checksJsonBoundariesWithoutTakingAbsoluteValueOfLongMin() {
        assertThat(new MoneyMinor("AED", JSON_LIMIT).requireJsonSafe().minor()).isEqualTo(JSON_LIMIT);
        assertThat(new MoneyMinor("AED", -JSON_LIMIT).requireJsonSafe().minor()).isEqualTo(-JSON_LIMIT);
        for (long value : new long[] {JSON_LIMIT + 1, -JSON_LIMIT - 1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> new MoneyMinor("AED", value).requireJsonSafe())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nonNegativeIsAnExplicitBoundaryRatherThanAnArithmeticRestriction() {
        assertThat(new MoneyMinor("AED", 0).requireNonNegative()).isEqualTo(new MoneyMinor("AED", 0));
        assertThatThrownBy(() -> new MoneyMinor("AED", -1).requireNonNegative())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
