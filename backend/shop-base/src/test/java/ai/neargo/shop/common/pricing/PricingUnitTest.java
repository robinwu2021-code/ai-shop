package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PricingUnitTest {
    @Test
    void preservesFractionalQuantityAndNormalizesInsignificantZeros() {
        PricingUnit unit = unit("0.1000000", "0.3000000");
        assertThat(unit.quantityStart()).isEqualTo(new BigDecimal("0.1"));
        assertThat(unit.quantityEnd()).isEqualTo(new BigDecimal("0.3"));
        assertThat(unit.quantity()).isEqualTo(new BigDecimal("0.2"));
        assertThat(unit("0", "0.000001").quantity()).isEqualTo(new BigDecimal("0.000001"));
        assertThat(unit.baseAmount()).isEqualTo(new MoneyMinor("AED", 21));
    }

    @Test
    void rejectsPrecisionLossAndDecimalColumnOverflow() {
        assertThatThrownBy(() -> unit("0", "0.0000001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unit("0", "100000000000000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unit("0", "1E+1000000")).isInstanceOf(IllegalArgumentException.class);
        assertThat(unit("0", "99999999999999.999999").quantity())
                .isEqualTo(new BigDecimal("99999999999999.999999"));
    }

    @Test
    void rejectsNegativeEmptyAndReversedRanges() {
        assertThatThrownBy(() -> unit("-0.1", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unit("1", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unit("2", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsRealComponentAndInvoiceIdentityInsteadOfDerivingThemFromSku() {
        PricingUnit unit = unit("0", "1");
        assertThat(unit.componentRef()).isNull();
        assertThat(unit.taxInvoiceLineRef()).isEqualTo("INVOICE-L1");
        assertThatThrownBy(() -> new PricingUnit("L1", "", "G1", "S1", "V1", "INVOICE-L1",
                BigDecimal.ZERO, BigDecimal.ONE, new MoneyMinor("AED", 100), new MoneyMinor("AED", 100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingUnit(" L1", null, "G1", "S1", "V1", "INVOICE-L1",
                BigDecimal.ZERO, BigDecimal.ONE, new MoneyMinor("AED", 100), new MoneyMinor("AED", 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyNonNegativeSafeAmountsInTheSameCurrency() {
        assertThatThrownBy(() -> priced(new MoneyMinor("AED", 100), new MoneyMinor("CNY", 100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> priced(new MoneyMinor("AED", -1), new MoneyMinor("AED", 100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> priced(new MoneyMinor("AED", 100), new MoneyMinor("AED", -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> priced(new MoneyMinor("AED", 100), new MoneyMinor("AED", Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PricingUnit unit(String start, String end) {
        return new PricingUnit("L1", null, "G1", "S1", "V1", "INVOICE-L1",
                new BigDecimal(start), new BigDecimal(end), new MoneyMinor("AED", 105), new MoneyMinor("AED", 21));
    }

    private static PricingUnit priced(MoneyMinor unitPrice, MoneyMinor amount) {
        return new PricingUnit("L1", null, "G1", "S1", "V1", "INVOICE-L1",
                BigDecimal.ZERO, BigDecimal.ONE, unitPrice, amount);
    }
}
