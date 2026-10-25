package ai.neargo.shop.report.support;

import java.math.BigDecimal;
import java.util.Map;

import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportMetricCalculatorTest {
    @Test void unknownDoesNotBecomeZero() {
        var unknown=new MetricValue(ValueType.MONEY,null,null,null,"AED",null,Coverage.UNKNOWN,"COST_NOT_READY",null,null,null);
        var sum=ReportMetricCalculator.add(MetricValue.money(120,"AED"),unknown);
        assertThat(sum.amountMinor()).isNull();
        assertThat(sum.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(sum.knownAmount()).isEqualTo(120L);
    }
    @Test void neverAddsCurrenciesOrUnits() {
        assertThatThrownBy(() -> ReportMetricCalculator.add(MetricValue.money(1,"AED"),MetricValue.money(1,"CNY")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportMetricCalculator.add(MetricValue.quantity(BigDecimal.ONE,"KG"),MetricValue.quantity(BigDecimal.ONE,"L")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsOverflowAndSilentDecimalRounding() {
        assertThatThrownBy(() -> ReportMetricCalculator.add(MetricValue.money(Long.MAX_VALUE,"AED"),MetricValue.money(1,"AED")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> MetricValue.quantity(new BigDecimal("0.0000001"),"KG"))
                .isInstanceOf(ArithmeticException.class);
    }
    @Test void usesWeightedNumeratorAndDenominator() {
        assertThat(ReportMetricCalculator.ratio(new BigDecimal("190"),new BigDecimal("10"))).isEqualByComparingTo("19");
        assertThat(ReportMetricCalculator.ratio(BigDecimal.ONE,BigDecimal.ZERO)).isNull();
    }
    @Test void catalogHashIsStableAcrossInstances() {
        assertThat(new ReportMetricCatalog().hash()).isEqualTo(new ReportMetricCatalog().hash());
    }
}
