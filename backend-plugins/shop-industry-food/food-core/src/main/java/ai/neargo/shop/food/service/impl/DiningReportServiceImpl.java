package ai.neargo.shop.food.service.impl;

import java.time.LocalDate;
import ai.neargo.shop.svc.ReportServiceResponses;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.spi.report.ReportContracts;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportQueryPort;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import ai.neargo.shop.spi.report.ReportQueryPort.Mode;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import org.springframework.beans.factory.ObjectProvider;

/** Industry adapter checks identity; shared report module owns aggregation and published evidence. */
public final class DiningReportServiceImpl implements DiningReportService {
    private final ObjectProvider<ReportQueryPort> reports;
    private final StoreCardQueryPort stores;
    private final ObjectProvider<ReportExportPort> exports;
    private final ObjectProvider<ReportRealtimePort> realtime;
    private final ObjectProvider<ReportMetricCatalogPort> catalog;
    public DiningReportServiceImpl(ObjectProvider<ReportQueryPort> reports,StoreCardQueryPort stores,ObjectProvider<ReportRealtimePort> realtime,
            ObjectProvider<ReportMetricCatalogPort> catalog,ObjectProvider<ReportExportPort> exports) {
        this.reports=reports; this.stores=stores; this.realtime=realtime; this.catalog=catalog; this.exports=exports;
    }
    @Override public List<MetricDefinition> metrics() { return ReportServiceResponses.domain(this::metricsInternal); }
    private List<MetricDefinition> metricsInternal() {
        var owner=catalog.getIfUnique();
        if (owner==null) throw new IllegalStateException("REPORT_CATALOG_OWNER_NOT_AVAILABLE");
        return owner.metrics();
    }
    @Override public ReportRealtimePort.Result realtime(String entity,String store,String actor,int version,boolean inventory) {
        return ReportServiceResponses.domain(()-> {
        ReportContracts.identifier(entity,64); ReportContracts.identifier(actor,64);
        var card=stores.cards(List.of(store)).get(store);
        if (card==null || !entity.equals(card.entityNo())) throw new IllegalArgumentException("REPORT_STORE_NOT_OWNED");
        var owner=realtime.getIfUnique();
        if (owner==null) throw new IllegalStateException("REPORT_REALTIME_OWNER_NOT_AVAILABLE");
        return owner.summary(new ReportRealtimePort.Query(new Scope("MAIN",entity,store),actor,version,inventory));
        });
    }
    @Override public Result query(String entity,List<String> numbers,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit) {
        return ReportServiceResponses.domain(()->report().query(prepare(entity,numbers,actor,view,from,to,version,codes,limit)));
    }
    @Override public ReportExportPort.Task export(String entity,List<String> numbers,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit,String key) {
        return ReportServiceResponses.domain(()->exporter().create(new ReportExportPort.Create(prepare(entity,numbers,actor,view,from,to,version,codes,limit),key)));
    }
    @Override public Result query(String entity,List<String> numbers,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit,MeasureFilter measure) {
        return ReportServiceResponses.domain(()->report().query(withMeasure(prepare(entity,numbers,actor,view,from,to,version,codes,limit),measure)));
    }
    @Override public ReportExportPort.Task export(String entity,List<String> numbers,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit,String key,MeasureFilter measure) {
        return ReportServiceResponses.domain(()->exporter().create(new ReportExportPort.Create(withMeasure(prepare(entity,numbers,actor,view,from,to,version,codes,limit),measure),key)));
    }
    private Query withMeasure(Query q,MeasureFilter measure) {
        return measure==null?q:new Query(q.scopes(),q.from(),q.to(),q.metricVersion(),q.aggregationSet(),q.metricCodes(),measure.filters(),q.mode(),q.limit(),q.actorNo());
    }
    @Override public ReportExportPort.Task getExport(String entity,String actor,String exportNo) {
        return ReportServiceResponses.domain(()->exporter().get(new ReportExportPort.Reference("MAIN",entity,actor,exportNo)));
    }
    @Override public ReportExportPort.Download downloadExport(String entity,String actor,String exportNo) {
        return ReportServiceResponses.domain(()->exporter().download(new ReportExportPort.Reference("MAIN",entity,actor,exportNo)));
    }
    private ReportQueryPort report() {
        var report=reports.getIfUnique();
        if(report==null) throw new IllegalStateException("REPORT_QUERY_OWNER_NOT_AVAILABLE");
        return report;
    }
    private ReportExportPort exporter() {
        var export=exports.getIfUnique();
        if(export==null) throw new IllegalStateException("REPORT_EXPORT_OWNER_NOT_AVAILABLE");
        return export;
    }
    private Query prepare(String entity,List<String> numbers,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit) {
        ReportContracts.identifier(entity,64); ReportContracts.identifier(actor,64);
        if (numbers==null || numbers.isEmpty() || numbers.size()>30 || Set.copyOf(numbers).size()!=numbers.size())
            throw new IllegalArgumentException("REPORT_INVALID_STORE_SCOPE");
        var cards=stores.cards(numbers);
        if (numbers.stream().anyMatch(n->!cards.containsKey(n)||!entity.equals(cards.get(n).entityNo())))
            throw new IllegalArgumentException("REPORT_STORE_NOT_OWNED");
        String set=switch(view) {
            case PAYMENTS -> "PAYMENT_CHANNEL_DAY";
            case DISCOUNTS -> "DISCOUNT_TYPE_DAY";
            case REFUNDS -> "REFUND_CHANNEL_DAY";
            case PROFIT -> "PROFIT_COST_DAY";
            case RANKINGS -> "DISH_COMPONENT_DAY";
            case INVENTORY -> "INVENTORY_BALANCE_DAY";
            case INVENTORY_MOVEMENT -> "INVENTORY_MOVEMENT_DAY";
            default -> "OPERATING_ORDER_DAY";
        };
        Mode mode=switch(view) {
            case PERIOD,PROFIT,PAYMENTS,DISCOUNTS,REFUNDS -> Mode.PERIOD;
            case RANKINGS -> Mode.RANKING;
            case TREND_WEEK -> Mode.WEEKLY;
            case TREND_MONTH -> Mode.MONTHLY;
            case HEALTH -> Mode.HEALTH;
            default -> Mode.DAILY;
        };
        Set<String> selected=codes==null||codes.isEmpty()?defaults(view):codes;
        return new Query(numbers.stream().map(n->new Scope("MAIN",entity,n)).toList(),from,to,
                version,set,selected,Map.of(),mode,limit,actor);
    }
    private Set<String> defaults(View view) {
        return switch(view) {
            case PAYMENTS -> Set.of("PAYMENT_SUCCESS_COUNT","PAYMENT_SUCCESS_AMOUNT","PAYMENT_FEE_AMOUNT");
            case DISCOUNTS -> Set.of("GOODS_DISCOUNT_AMOUNT");
            case REFUNDS -> Set.of("REFUND_SUCCESS_COUNT","REFUND_SUCCESS_AMOUNT");
            case PROFIT -> Set.of("GOODS_GROSS_PROFIT_AMOUNT","GOODS_GROSS_PROFIT_RATE","OPERATING_NET_PROFIT_AMOUNT","OPERATING_NET_PROFIT_RATE");
            case RANKINGS -> Set.of("DISH_ORDERED_QTY");
            case INVENTORY_MOVEMENT -> Set.of("STOCK_RECEIPT_QTY","STOCK_OUTBOUND_QTY");
            case INVENTORY -> Set.of("STOCK_OPENING_QTY","STOCK_CLOSING_QTY","STOCK_AVAILABLE_QTY","STOCK_RESERVED_QTY");
            default -> Set.of("ORDER_CREATED_COUNT","ORDER_TRANSACTED_COUNT","ORDER_TRANSACTED_AMOUNT");
        };
    }
}
