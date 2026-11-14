package ai.neargo.shop.report.support;

import java.util.HashMap;
import java.util.Map;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportProfitCalculatorTest {
    @Test void calculatesTaxExclusiveProfitWithoutSubtractingDiscountsOrReturnsAgain() {
        var inputs=inputs();inputs.put("GOODS_TRANSACTED_NET_AMOUNT",MetricValue.money(10000,"AED"));
        inputs.put("GOODS_SALES_COST_AMOUNT",MetricValue.money(4000,"AED"));
        inputs.put("OPERATING_OTHER_INCOME_AMOUNT",MetricValue.money(500,"AED"));
        inputs.put("PAYMENT_ALLOCATED_FEE_COST_AMOUNT",MetricValue.money(100,"AED"));
        inputs.put("DELIVERY_FEE_COST_AMOUNT",MetricValue.money(200,"AED"));
        inputs.put("PLATFORM_COMMISSION_AMOUNT",MetricValue.money(300,"AED"));
        inputs.put("OPERATING_EXPENSE_AMOUNT",MetricValue.money(1000,"AED"));
        inputs.put("OPERATING_UNALLOCATED_LOSS_AMOUNT",MetricValue.money(50,"AED"));
        var result=ReportProfitCalculator.calculate(inputs,"AED");
        assertThat(result.get(ReportProfitCalculator.GROSS).amountMinor()).isEqualTo(6000L);
        assertThat(result.get(ReportProfitCalculator.NET).amountMinor()).isEqualTo(4850L);
        assertThat(result.get(ReportProfitCalculator.GROSS_RATE).quantityValue()).isEqualByComparingTo("0.6");
    }
    @Test void missingExpenseIsNotZeroAndDoesNotEraseKnownGrossProfit() {
        var inputs=inputs();inputs.put("GOODS_TRANSACTED_NET_AMOUNT",MetricValue.money(100,"AED"));
        inputs.remove("OPERATING_EXPENSE_AMOUNT");
        var result=ReportProfitCalculator.calculate(inputs,"AED");
        assertThat(result.get(ReportProfitCalculator.GROSS).amountMinor()).isEqualTo(100L);
        assertThat(result.get(ReportProfitCalculator.NET).amountMinor()).isNull();
        assertThat(result.get(ReportProfitCalculator.NET).knownAmount()).isNull();
    }
    @Test void mixedCurrenciesAndOverflowFailRatherThanConvertOrClamp() {
        var inputs=inputs();inputs.put("GOODS_TRANSACTED_NET_AMOUNT",MetricValue.money(100,"CNY"));
        assertThatThrownBy(()->ReportProfitCalculator.calculate(inputs,"AED")).hasMessage("REPORT_PROFIT_CURRENCY_MISMATCH");
        inputs.put("GOODS_TRANSACTED_NET_AMOUNT",MetricValue.money(Long.MAX_VALUE,"AED"));
        inputs.put("OPERATING_OTHER_INCOME_AMOUNT",MetricValue.money(1,"AED"));
        assertThatThrownBy(()->ReportProfitCalculator.calculate(inputs,"AED")).isInstanceOf(ArithmeticException.class);
    }
    @Test void zeroRevenueHasNoArtificialProfitRate() {
        var result=ReportProfitCalculator.calculate(inputs(),"AED");
        assertThat(result.get(ReportProfitCalculator.GROSS).amountMinor()).isZero();
        assertThat(result.get(ReportProfitCalculator.GROSS_RATE).quantityValue()).isNull();
        assertThat(result.get(ReportProfitCalculator.GROSS_RATE).unknownReason()).isEqualTo("ZERO_PROFIT_DENOMINATOR");
    }
    private Map<String,MetricValue> inputs() {
        var inputs=new HashMap<String,MetricValue>();
        ReportProfitCalculator.INPUTS.forEach(code->inputs.put(code,MetricValue.money(0,"AED")));return inputs;
    }
}
