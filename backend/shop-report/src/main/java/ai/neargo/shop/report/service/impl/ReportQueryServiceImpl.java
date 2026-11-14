package ai.neargo.shop.report.service.impl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.Row;
import ai.neargo.shop.spi.report.ReportQueryPort.Mode;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;
import ai.neargo.shop.spi.report.ReportQueryPort.SourceWatermark;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

import ai.neargo.shop.report.service.ReportQueryService;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.support.ReportDimensionKey;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.report.support.ReportProfitCalculator;
import ai.neargo.shop.report.support.ReportProfitProjection;
import ai.neargo.shop.spi.report.ReportContracts.Aggregation;
import ai.neargo.shop.spi.report.ReportContracts.BuildProblem;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Publication;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;

/** Published facts only. Missing days and missing metrics never become zero. */
public final class ReportQueryServiceImpl implements ReportQueryService {
    private TransactionTemplate snapshotRead;
    private final ReportStorageService storage;
    private final ReportMetricCatalog catalog;
    private final int rowLimit;
    public ReportQueryServiceImpl(ReportStorageService storage,ReportMetricCatalog catalog,int rowLimit) {
        this.storage=storage; this.catalog=catalog; this.rowLimit=rowLimit;
    }
    public ReportQueryServiceImpl(ReportStorageService storage,ReportMetricCatalog catalog,int rowLimit,
            PlatformTransactionManager manager) {
        this(storage,catalog,rowLimit);
        snapshotRead=new TransactionTemplate(manager);
        snapshotRead.setReadOnly(true);
        snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public List<MetricDefinition> metrics() {
        return catalog.definitions().stream().map(d->new MetricDefinition(d.code(),d.source(),d.type(),d.aggregation(),
                d.sets(),d.dimensions(),d.negativeAllowed(),catalog.availability(d.code()))).toList();
    }
    @Override public Result query(Query query) {
        return snapshotRead==null?querySnapshot(query):snapshotRead.execute(status->querySnapshot(query));
    }
    private Result querySnapshot(Query query) {
        validate(query);
        int version=version(query);
        Set<String> inputCodes=new LinkedHashSet<>(query.metricCodes());
        boolean profits=query.metricCodes().stream().anyMatch(ReportProfitCalculator.OUTPUTS::contains);
        if(profits) { inputCodes.removeAll(ReportProfitCalculator.OUTPUTS);inputCodes.addAll(ReportProfitCalculator.INPUTS); }
        List<Row> rows=new ArrayList<>(); List<Publication> builds=new ArrayList<>();
        List<BuildProblem> problems=new ArrayList<>();
        List<Partition> missing=new ArrayList<>(); List<SourceWatermark> watermarks=new ArrayList<>();
        for (Scope scope:query.scopes()) {
            if(query.mode()==Mode.HEALTH) {
                problems.addAll(storage.buildProblems(scope,query.from(),query.to(),version,1000));
                if(problems.size()>1000) throw new IllegalArgumentException("REPORT_QUERY_METADATA_LIMIT");
            }
            var days=storage.readDays(scope,query.from(),query.to(),version);
            Map<LocalDate,Boolean> found=new HashMap<>();
            for (var day:days) {
                LocalDate date=day.publication().partition().businessDate(); found.put(date,true); builds.add(day.publication());
                day.sources().forEach(source->watermarks.add(new SourceWatermark(day.publication().partition(),source.source(),
                        source.asOfAt(),source.coverage(),source.unknownReason())));
                for (String code:inputCodes) {
                    var definition=catalog.get(code);
                    if(measureUnsupported(definition.type(),query)) {
                        String reason=query.filters().containsKey("currency")?"CURRENCY_COUNT_ALLOCATION_UNAVAILABLE":"UNIT_COUNT_ALLOCATION_UNAVAILABLE";
                        rows.add(new Row(scope,date,unknown(code,query,reason)));continue;
                    }
                    var source=day.sources().stream().filter(s->s.source().equals(definition.source())).findFirst();
                    boolean available=source.isPresent() && (source.get().coverage()==Coverage.COMPLETE
                            || source.get().coverage()==Coverage.EMPTY_VERIFIED);
                    var facts=day.facts().stream().filter(f->f.metricCode().equals(code)
                            && f.aggregationSet().equals(query.aggregationSet())
                            && dimensionFilters(query).entrySet().stream().allMatch(e->Objects.equals(f.dimensions().get(e.getKey()),e.getValue()))
                            && measureMatches(f.value(),query)).map(f->measure(f,query)).toList();
                    if(facts.isEmpty()&&available&&definition.type()==ValueType.MONEY&&query.filters().containsKey("currency")
                            &&dimensionFilters(query).isEmpty()&&"READ_ADAPTER_IMPLEMENTED".equals(catalog.availability(code))) {
                        rows.add(new Row(scope,date,new MetricFact(code,query.aggregationSet(),Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),
                                MetricValue.money(0,query.filters().get("currency")))));continue;
                    }
                    if (facts.isEmpty()&&available&&catalog.emptyGroupsAreKnown(code)) continue;
                    if (facts.isEmpty()) {
                        rows.add(new Row(scope,date,unknown(code,query,"METRIC_NOT_OBSERVED")));
                    } else for (MetricFact fact:facts) {
                        rows.add(new Row(scope,date,available?fact:incomplete(fact,"SOURCE_INCOMPLETE")));
                    }
                    if (rows.size()>rowLimit) throw new IllegalArgumentException("REPORT_QUERY_ROW_LIMIT");
                }
            }
            for (LocalDate date=query.from();date.isBefore(query.to());date=date.plusDays(1)) {
                if (!found.containsKey(date)) missing.add(new Partition(scope,date,version));
            }
        }
        if (query.mode()!=Mode.DAILY && query.mode()!=Mode.HEALTH) {
            rows=period(rows,query);
            rows=rows.stream().map(row->missing.stream().anyMatch(p->p.scope().equals(row.scope()) && Objects.equals(bucket(p.businessDate(),query.mode()),row.businessDate()))
                    ?new Row(row.scope(),row.businessDate(),incomplete(row.fact(),"MISSING_DAILY_PARTITION")):row).toList();
        }
        if(profits) rows=ReportProfitProjection.derive(rows);
        rows=rows.stream().filter(row->query.metricCodes().contains(row.fact().metricCode())).toList();
        boolean complete=missing.isEmpty() && rows.stream().allMatch(r->ReportMetricCalculator.known(r.fact().value()));
        if (query.mode()==Mode.RANKING) {
            if (!complete) throw new IllegalStateException("REPORT_INCOMPLETE_RANKING");
            // Sorting money/quantity in separate units is meaningless. Require one metric and one measure.
            long measures=rows.stream().map(r->List.of(Objects.toString(r.fact().value().currency(),""),
                    Objects.toString(r.fact().value().unit(),""))).distinct().count();
            if (measures>1) throw new IllegalArgumentException("REPORT_RANKING_MIXED_MEASURES");
            rows=rows.stream().sorted(Comparator.<Row,BigDecimal>comparing(r->number(r.fact().value())).reversed()
                    .thenComparing(r->r.scope().storeNo()).thenComparing(r->ReportDimensionKey.of(r.fact())))
                    .limit(query.limit()).toList();
        } else if (rows.size()>query.limit()) throw new IllegalArgumentException("REPORT_QUERY_RESULT_LIMIT");
        return new Result(version,rows,builds,missing,complete,query.scopes(),query.from(),query.to(),"DAILY","BUSINESS_DATE",watermarks,problems);
    }
    private void validate(Query q) {
        new MeasureFilter(q.filters().get("currency"),q.filters().get("unit"));
        for (String code:q.metricCodes()) {
            var d=catalog.get(code);
            if (!d.sets().contains(q.aggregationSet()) || !d.dimensions().containsAll(dimensionFilters(q).keySet()))
                throw new IllegalArgumentException("REPORT_QUERY_GRAIN_MISMATCH");
            if (q.mode()!=Mode.DAILY && d.aggregation()!=Aggregation.SUM && d.aggregation()!=Aggregation.DERIVED)
                throw new IllegalArgumentException("REPORT_NONADDITIVE_PERIOD_QUERY");
        }
        if(q.filters().containsKey("currency")&&q.metricCodes().stream().noneMatch(c->catalog.get(c).type()==ValueType.MONEY))
            throw new IllegalArgumentException("REPORT_CURRENCY_GRAIN_UNSUPPORTED");
        if(q.filters().containsKey("unit")&&q.metricCodes().stream().noneMatch(c->catalog.get(c).type()==ValueType.QUANTITY))
            throw new IllegalArgumentException("REPORT_UNIT_GRAIN_UNSUPPORTED");
        if (q.metricCodes().stream().anyMatch(ReportProfitCalculator.OUTPUTS::contains)&&!q.filters().isEmpty())
            throw new IllegalArgumentException("REPORT_PROFIT_FILTER_NOT_SUPPORTED");
        if (q.mode()==Mode.RANKING && q.metricCodes().size()!=1)
            throw new IllegalArgumentException("REPORT_RANKING_SINGLE_METRIC_REQUIRED");
    }
    private int version(Query q) {
        if (q.metricVersion()!=null) return q.metricVersion();
        Integer selected=null;
        for (Scope scope:q.scopes()) {
            var configured=storage.scopes(scope).stream().filter(c->c.enabled()).toList();
            var defaults=configured.stream().filter(c->c.defaultForReads()).toList();
            if(q.mode()==Mode.HEALTH&&defaults.isEmpty()&&configured.size()==1) defaults=configured;
            if (defaults.size()!=1) throw new IllegalStateException("REPORT_DEFAULT_VERSION_NOT_READY");
            int v=defaults.getFirst().partition().metricVersion();
            if (selected!=null && selected!=v) throw new IllegalStateException("REPORT_MIXED_DEFAULT_VERSIONS");
            selected=v;
        }
        return Objects.requireNonNull(selected);
    }
    private List<Row> period(List<Row> rows,Query q) {
        Map<String,Row> sums=new LinkedHashMap<>();
        for (Row row:rows) {
            LocalDate date=bucket(row.businessDate(),q.mode());
            String key=row.scope().storeNo()+":"+date+":"+row.fact().metricCode()+":"+ReportDimensionKey.of(row.fact());
            Row previous=sums.get(key);
            MetricFact fact=previous==null?row.fact():ReportMetricCalculator.merge(previous.fact(),row.fact());
            sums.put(key,new Row(row.scope(),date,fact));
        }
        return List.copyOf(sums.values());
    }
    private static LocalDate bucket(LocalDate date,Mode mode) {
        return switch(mode) {
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> date.withDayOfMonth(1);
            default -> null;
        };
    }
    private MetricFact unknown(String code,Query q,String reason) {
        var type=catalog.get(code).type();
        return new MetricFact(code,q.aggregationSet(),Attribution.ORIGINAL_ORDER,
                dimensionFilters(q),Map.of(),new MetricValue(type,null,null,null,type==ValueType.MONEY?q.filters().get("currency"):null,type==ValueType.QUANTITY?q.filters().get("unit"):null,Coverage.UNKNOWN,reason,null,null,null));
    }
    private static Map<String,String> dimensionFilters(Query q) {
        var dimensions=new LinkedHashMap<>(q.filters());dimensions.remove("currency");dimensions.remove("unit");return dimensions;
    }
    private static boolean measureUnsupported(ValueType type,Query q) {
        return q.filters().containsKey("currency")&&type!=ValueType.MONEY || q.filters().containsKey("unit")&&type!=ValueType.QUANTITY;
    }
    private static boolean measureMatches(MetricValue value,Query q) {
        boolean unknown=!ReportMetricCalculator.known(value);
        return (value.type()!=ValueType.MONEY || !q.filters().containsKey("currency") || Objects.equals(q.filters().get("currency"),value.currency()) || unknown&&value.currency()==null)
                && (value.type()!=ValueType.QUANTITY || !q.filters().containsKey("unit") || Objects.equals(q.filters().get("unit"),value.unit()) || unknown&&value.unit()==null);
    }
    private static MetricFact measure(MetricFact fact,Query q) {
        var v=fact.value();if(ReportMetricCalculator.known(v)) return fact;
        var value=new MetricValue(v.type(),null,null,null,v.type()==ValueType.MONEY&&v.currency()==null?q.filters().get("currency"):v.currency(),
                v.type()==ValueType.QUANTITY&&v.unit()==null?q.filters().get("unit"):v.unit(),v.coverage(),v.unknownReason(),v.knownInteger(),v.knownQuantity(),v.knownAmount());
        return new MetricFact(fact.metricCode(),fact.aggregationSet(),fact.attribution(),fact.dimensions(),fact.labels(),value);
    }
    private static MetricFact incomplete(MetricFact fact,String reason) {
        MetricValue v=fact.value();
        if (!ReportMetricCalculator.known(v)) return fact;
        return new MetricFact(fact.metricCode(),fact.aggregationSet(),fact.attribution(),fact.dimensions(),fact.labels(),
                new MetricValue(v.type(),null,null,null,v.currency(),v.unit(),Coverage.PARTIAL,reason,v.integerValue(),v.quantityValue(),v.amountMinor()));
    }
    private static BigDecimal number(MetricValue value) {
        if (value.quantityValue()!=null) return value.quantityValue();
        return BigDecimal.valueOf(value.amountMinor()!=null?value.amountMinor():value.integerValue());
    }
}
