package ai.neargo.shop.report.support;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;

/** Tax-exclusive, same-cohort operating profit. Inputs must be disjoint classifications; no second discount/refund subtraction. */
public final class ReportProfitCalculator {
    public static final String GROSS="GOODS_GROSS_PROFIT_AMOUNT",GROSS_RATE="GOODS_GROSS_PROFIT_RATE";
    public static final String NET="OPERATING_NET_PROFIT_AMOUNT",NET_RATE="OPERATING_NET_PROFIT_RATE";
    public static final Set<String> OUTPUTS=Set.of(GROSS,GROSS_RATE,NET,NET_RATE);
    public static final List<String> INPUTS=List.of("GOODS_TRANSACTED_NET_AMOUNT","GOODS_SALES_COST_AMOUNT",
            "OPERATING_OTHER_INCOME_AMOUNT","PAYMENT_ALLOCATED_FEE_COST_AMOUNT","DELIVERY_FEE_COST_AMOUNT",
            "PLATFORM_COMMISSION_AMOUNT","OPERATING_EXPENSE_AMOUNT","OPERATING_UNALLOCATED_LOSS_AMOUNT");
    private ReportProfitCalculator() {}
    public static Map<String,MetricValue> calculate(Map<String,MetricValue> inputs,String currency) {
        for (MetricValue value:inputs.values()) {
            if (value.type()!=ValueType.MONEY || value.currency()!=null && !value.currency().equals(currency))
                throw new IllegalArgumentException("REPORT_PROFIT_CURRENCY_MISMATCH");
        }
        var result=new LinkedHashMap<String,MetricValue>();
        boolean grossKnown=known(inputs,INPUTS.subList(0,2));
        Long gross=grossKnown?Math.subtractExact(amount(inputs,INPUTS.get(0)),amount(inputs,INPUTS.get(1))):null;
        boolean netKnown=known(inputs,INPUTS);
        Long net=null,revenue=null;
        if (netKnown) {
            revenue=Math.addExact(amount(inputs,INPUTS.get(0)),amount(inputs,INPUTS.get(2)));
            long value=Math.addExact(gross,amount(inputs,INPUTS.get(2)));
            for (String cost:INPUTS.subList(3,INPUTS.size())) value=Math.subtractExact(value,amount(inputs,cost));
            net=value;
        }
        result.put(GROSS,money(gross,currency));
        result.put(GROSS_RATE,rate(gross,grossKnown?amount(inputs,INPUTS.get(0)):null,currency));
        result.put(NET,money(net,currency));result.put(NET_RATE,rate(net,revenue,currency));
        return Map.copyOf(result);
    }
    private static boolean known(Map<String,MetricValue> values,List<String> codes) {
        return codes.stream().allMatch(c->values.containsKey(c)&&ReportMetricCalculator.known(values.get(c)));
    }
    private static long amount(Map<String,MetricValue> values,String code) { return values.get(code).amountMinor(); }
    private static MetricValue money(Long value,String currency) {
        return value==null?new MetricValue(ValueType.MONEY,null,null,null,currency,null,Coverage.UNKNOWN,
                "PROFIT_INPUT_NOT_COMPLETE",null,null,null):MetricValue.money(value,currency);
    }
    private static MetricValue rate(Long numerator,Long denominator,String currency) {
        BigDecimal value=numerator==null||denominator==null?null:ReportMetricCalculator.ratio(BigDecimal.valueOf(numerator),BigDecimal.valueOf(denominator));
        return new MetricValue(ValueType.QUANTITY,null,value,null,currency,"RATIO",value==null?Coverage.UNKNOWN:Coverage.COMPLETE,
                value==null?(denominator!=null&&denominator==0?"ZERO_PROFIT_DENOMINATOR":"PROFIT_INPUT_NOT_COMPLETE"):null,null,null,null);
    }
}
