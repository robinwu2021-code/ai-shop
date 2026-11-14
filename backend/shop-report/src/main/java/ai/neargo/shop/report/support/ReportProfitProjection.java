package ai.neargo.shop.report.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;
import ai.neargo.shop.spi.report.ReportQueryPort.Row;

/** Calculates after day/period aggregation. Unknown currency-wide inputs contaminate every affected currency. */
public final class ReportProfitProjection {
    private record Grain(Scope scope,LocalDate date) {}
    private ReportProfitProjection() {}
    public static List<Row> derive(List<Row> rows) {
        Map<Grain,List<Row>> groups=new LinkedHashMap<>();
        for (Row row:rows) groups.computeIfAbsent(new Grain(row.scope(),row.businessDate()),key->new ArrayList<>()).add(row);
        List<Row> result=new ArrayList<>(rows);
        for (var group:groups.entrySet()) {
            var currencies=new LinkedHashSet<String>();
            group.getValue().forEach(row->{ if(row.fact().value().currency()!=null) currencies.add(row.fact().value().currency()); });
            if(currencies.isEmpty()) currencies.add(null);
            for (String currency:currencies) {
                Map<String,MetricValue> inputs=new LinkedHashMap<>();
                for (String code:ReportProfitCalculator.INPUTS) {
                    MetricValue sum=null;
                    for (Row row:group.getValue()) {
                        var value=row.fact().value();
                        if (!row.fact().metricCode().equals(code)) continue;
                        if (value.currency()!=null&&!value.currency().equals(currency)) continue;
                        if(value.currency()==null) value=new MetricValue(ValueType.MONEY,null,null,null,currency,null,
                                Coverage.UNKNOWN,"PROFIT_INPUT_NOT_COMPLETE",null,null,null);
                        sum=sum==null?value:ReportMetricCalculator.add(sum,value);
                    }
                    if(sum==null) sum=new MetricValue(ValueType.MONEY,null,null,null,currency,null,Coverage.UNKNOWN,"METRIC_NOT_OBSERVED",null,null,null);
                    inputs.put(code,sum);
                }
                ReportProfitCalculator.calculate(inputs,currency).forEach((code,value)->result.add(new Row(group.getKey().scope(),group.getKey().date(),
                        new MetricFact(code,"PROFIT_COST_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),value))));
            }
        }
        return List.copyOf(result);
    }
}
