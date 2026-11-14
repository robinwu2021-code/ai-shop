package ai.neargo.shop.food.spi;
import ai.neargo.shop.spi.report.ReportAffectedDaysPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.food.service.DiningReportService.View;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportDayPort.OperatingDay;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface DiningReportInternalApi {
    @PostExchange("/internal/food/operating-report/affected-days")
    List<ReportAffectedDaysPort.AffectedDay> affectedDays(
            @RequestBody ReportAffectedDaysPort.Change change);
    record RealtimeQuery(String entity,String store,String actor,int version,boolean inventory) {}
    @PostExchange("/internal/food/operating-report/realtime") ReportRealtimePort.Result realtime(@RequestBody RealtimeQuery query);
    @PostExchange("/internal/food/operating-report/metrics") List<MetricDefinition> metrics();
    @PostExchange("/internal/food/operating-report/current-day") OperatingDay currentDay(@RequestBody Scope scope);
    record ExportQuery(Query query,String key) {}
    record ExportReference(String entity,String actor,String exportNo) {}
    @PostExchange("/internal/food/operating-report/export") ReportExportPort.Task export(@RequestBody ExportQuery query);
    @PostExchange("/internal/food/operating-report/export-status") ReportExportPort.Task exportStatus(@RequestBody ExportReference reference);
    @PostExchange("/internal/food/operating-report/export-download") ReportExportPort.Download exportDownload(@RequestBody ExportReference reference);
    String QUERY="/internal/food/operating-report/query";
    String FACTS="/internal/food/operating-report/facts";
    String DAY="/internal/food/operating-report/day";
    record Query(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> metricCodes,int limit,MeasureFilter measure) {
        public Query(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,Integer version,Set<String> metricCodes,int limit) {
            this(entity,stores,actor,view,from,to,version,metricCodes,limit,null);
        }
    }
    record DayQuery(Scope scope,LocalDate date) {}
    @PostExchange(QUERY) Result query(@RequestBody Query query);
    @PostExchange(FACTS) SourceSnapshot facts(@RequestBody SourceQuery query);
    @PostExchange(DAY) DayEvidence day(@RequestBody DayQuery query);
}
