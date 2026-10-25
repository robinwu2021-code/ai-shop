package ai.neargo.shop.food.server.api;
import ai.neargo.shop.spi.report.ReportAffectedDaysPort;
import org.springframework.http.ResponseEntity;

import ai.neargo.shop.svc.ReportServiceResponses;
import org.springframework.web.bind.annotation.ExceptionHandler;

import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import org.springframework.context.annotation.Conditional;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.food.spi.DiningReportInternalApi;
import ai.neargo.shop.food.spi.DiningReportInternalApi.DayQuery;
import ai.neargo.shop.food.spi.DiningReportInternalApi.Query;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.food.spi.DiningReportInternalApi.ExportQuery;
import ai.neargo.shop.food.spi.DiningReportInternalApi.ExportReference;
import ai.neargo.shop.spi.report.ReportDayPort.OperatingDay;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import java.util.List;
import ai.neargo.shop.food.spi.DiningReportInternalApi.RealtimeQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service authentication is enforced by the original food-server internal security chain. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningReportController {
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> reportFailure(RuntimeException failure) {
        return ReportServiceResponses.error(failure);
    }

    @PostMapping("/internal/food/operating-report/affected-days")
    public List<ReportAffectedDaysPort.AffectedDay> affectedDays(
            @RequestBody ReportAffectedDaysPort.Change change) {
        return facts.affectedDays(change);
    }
    private final DiningReportService reports;
    private final DiningReportFactsService facts;
    public InternalDiningReportController(DiningReportService reports,DiningReportFactsService facts) {
        this.reports=reports; this.facts=facts;
    }
    @PostMapping("/internal/food/operating-report/realtime") public ReportRealtimePort.Result realtime(@RequestBody RealtimeQuery q) {
        return reports.realtime(q.entity(),q.store(),q.actor(),q.version(),q.inventory());
    }
    @PostMapping("/internal/food/operating-report/metrics") public List<MetricDefinition> metrics() { return reports.metrics(); }
    @PostMapping("/internal/food/operating-report/current-day") public OperatingDay currentDay(@RequestBody Scope scope) { return facts.current(scope); }
    @PostMapping("/internal/food/operating-report/export") public ReportExportPort.Task export(@RequestBody ExportQuery command) {
        var q=command.query();return reports.export(q.entity(),q.stores(),q.actor(),q.view(),q.from(),q.to(),q.version(),q.metricCodes(),q.limit(),command.key(),q.measure());
    }
    @PostMapping("/internal/food/operating-report/export-status") public ReportExportPort.Task exportStatus(@RequestBody ExportReference ref) {
        return reports.getExport(ref.entity(),ref.actor(),ref.exportNo());
    }
    @PostMapping("/internal/food/operating-report/export-download") public ReportExportPort.Download exportDownload(@RequestBody ExportReference ref) {
        return reports.downloadExport(ref.entity(),ref.actor(),ref.exportNo());
    }
    @PostMapping(DiningReportInternalApi.QUERY) public Result query(@RequestBody Query q) {
        return reports.query(q.entity(),q.stores(),q.actor(),q.view(),q.from(),q.to(),q.version(),q.metricCodes(),q.limit(),q.measure());
    }
    @PostMapping(DiningReportInternalApi.FACTS) public SourceSnapshot facts(@RequestBody SourceQuery q) { return facts.readSnapshot(q); }
    @PostMapping(DiningReportInternalApi.DAY) public DayEvidence day(@RequestBody DayQuery q) { return facts.day(q.scope(),q.date()); }
}
