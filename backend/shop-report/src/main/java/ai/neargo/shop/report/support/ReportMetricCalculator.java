package ai.neargo.shop.report.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ai.neargo.shop.spi.report.ReportContracts.Aggregation;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;

/** Same pure calculation for live and daily. Unknown input stays unknown with an explicit subtotal. */
public final class ReportMetricCalculator {
    private final ReportMetricCatalog catalog;
    public ReportMetricCalculator(ReportMetricCatalog catalog) { this.catalog=catalog; }

    public List<MetricFact> aggregate(Partition partition,List<SourceSnapshot> snapshots) {
        Map<String,MetricFact> values=new LinkedHashMap<>();
        var seen=new HashSet<SourceKey>();
        for (SourceSnapshot snapshot:snapshots) {
            if (!snapshot.partition().equals(partition) || !seen.add(snapshot.source()))
                throw new IllegalArgumentException("REPORT_SOURCE_SCOPE_MISMATCH");
            for (MetricFact point:snapshot.facts()) {
                catalog.validate(point,snapshot.source());
                if (snapshot.coverage()!=Coverage.COMPLETE && snapshot.coverage()!=Coverage.EMPTY_VERIFIED && known(point.value())) {
                    var v=point.value();
                    point=new MetricFact(point.metricCode(),point.aggregationSet(),point.attribution(),point.dimensions(),point.labels(),
                            new MetricValue(v.type(),null,null,null,v.currency(),v.unit(),Coverage.PARTIAL,"SOURCE_INCOMPLETE",
                                    v.integerValue(),v.quantityValue(),v.amountMinor()));
                }
                String key=point.metricCode()+":"+ReportDimensionKey.of(point);
                MetricFact old=values.get(key);
                if (old!=null && catalog.get(point.metricCode()).aggregation()!=Aggregation.SUM)
                    throw new IllegalArgumentException("REPORT_NONADDITIVE_DUPLICATE");
                values.put(key,old==null?point:merge(old,point));
            }
        }
        return List.copyOf(values.values());
    }

    public static MetricFact merge(MetricFact a,MetricFact b) {
        if (!a.metricCode().equals(b.metricCode()) || !a.aggregationSet().equals(b.aggregationSet())
                || a.attribution()!=b.attribution() || !a.dimensions().equals(b.dimensions()))
            throw new IllegalArgumentException("REPORT_DIFFERENT_GRAIN");
        Map<String,String> labels=ReportEvidenceCodec.json(a.labels()).compareTo(ReportEvidenceCodec.json(b.labels()))<=0?a.labels():b.labels();
        return new MetricFact(a.metricCode(),a.aggregationSet(),a.attribution(),a.dimensions(),labels,add(a.value(),b.value()));
    }

    public static MetricValue add(MetricValue a,MetricValue b) {
        if (a.type()!=b.type() || !Objects.equals(a.currency(),b.currency()) || !Objects.equals(a.unit(),b.unit()))
            throw new IllegalArgumentException("REPORT_INCOMPATIBLE_MEASURE");
        boolean complete=known(a)&&known(b);
        Long integer=a.type()==ValueType.COUNT||a.type()==ValueType.DURATION?sum(integer(a),integer(b)):null;
        BigDecimal quantity=a.type()==ValueType.QUANTITY?sum(quantity(a),quantity(b)):null;
        Long money=a.type()==ValueType.MONEY?sum(money(a),money(b)):null;
        return new MetricValue(a.type(),complete?integer:null,complete?quantity:null,complete?money:null,a.currency(),a.unit(),
                complete?Coverage.COMPLETE:(integer!=null||quantity!=null||money!=null?Coverage.PARTIAL:Coverage.UNKNOWN),complete?null:"INCOMPLETE_INPUT",complete?null:integer,complete?null:quantity,complete?null:money);
    }
    public static boolean known(MetricValue v) { return v.coverage()==Coverage.COMPLETE||v.coverage()==Coverage.EMPTY_VERIFIED; }
    private static Long integer(MetricValue v) { return known(v)?v.integerValue():v.knownInteger(); }
    private static BigDecimal quantity(MetricValue v) { return known(v)?v.quantityValue():v.knownQuantity(); }
    private static Long money(MetricValue v) { return known(v)?v.amountMinor():v.knownAmount(); }
    private static Long sum(Long a,Long b) { return a==null?b:b==null?a:Math.addExact(a,b); }
    private static BigDecimal sum(BigDecimal a,BigDecimal b) { return a==null?b:b==null?a:a.add(b); }

    /** Never averages day averages. Missing or zero denominator remains null. */
    public static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator) {
        if (numerator==null||denominator==null||denominator.signum()==0) return null;
        return numerator.divide(denominator,6,RoundingMode.HALF_UP);
    }
}
