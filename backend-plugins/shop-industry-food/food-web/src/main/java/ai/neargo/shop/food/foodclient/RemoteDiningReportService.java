package ai.neargo.shop.food.foodclient;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.spi.DiningReportInternalApi;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.shop.svc.ReportServiceResponses;

public final class RemoteDiningReportService implements DiningReportService {
    private final DiningReportInternalApi api;
    public RemoteDiningReportService(DiningReportInternalApi api) { this.api=api; }
    @Override public List<MetricDefinition> metrics() { return ReportServiceResponses.call(ServiceName.FOOD,api::metrics); }
    @Override public ReportRealtimePort.Result realtime(String entity,String store,String actor,int version,boolean inventory) {
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.realtime(new DiningReportInternalApi.RealtimeQuery(entity,store,actor,version,inventory)));
    }
    @Override public ReportExportPort.Task export(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit,String key) {
        var query=new DiningReportInternalApi.Query(entity,stores,actor,view,from,to,version,codes,limit);
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.export(new DiningReportInternalApi.ExportQuery(query,key)));
    }
    @Override public ReportExportPort.Task getExport(String entity,String actor,String exportNo) {
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.exportStatus(new DiningReportInternalApi.ExportReference(entity,actor,exportNo)));
    }
    @Override public ReportExportPort.Download downloadExport(String entity,String actor,String exportNo) {
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.exportDownload(new DiningReportInternalApi.ExportReference(entity,actor,exportNo)));
    }
    @Override public Result query(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit,MeasureFilter measure) {
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.query(new DiningReportInternalApi.Query(entity,stores,actor,view,from,to,version,codes,limit,measure)));
    }
    @Override public ReportExportPort.Task export(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit,String key,MeasureFilter measure) {
        var query=new DiningReportInternalApi.Query(entity,stores,actor,view,from,to,version,codes,limit,measure);
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.export(new DiningReportInternalApi.ExportQuery(query,key)));
    }
    @Override public Result query(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> codes,int limit) {
        return ReportServiceResponses.call(ServiceName.FOOD,()->api.query(new DiningReportInternalApi.Query(entity,stores,actor,view,from,to,version,codes,limit)));
    }
}
