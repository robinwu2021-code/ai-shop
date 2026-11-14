package ai.neargo.shop.food.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.spi.report.ReportRealtimePort;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.MeasureFilter;

public interface DiningReportService {
    ReportRealtimePort.Result realtime(String entity,String store,String actor,int version,boolean inventory);
    List<MetricDefinition> metrics();
    ReportExportPort.Task export(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> metricCodes,int limit,String key);
    ReportExportPort.Task getExport(String entity,String actor,String exportNo);
    ReportExportPort.Download downloadExport(String entity,String actor,String exportNo);
    enum InventoryView { BALANCE, MOVEMENT }
    enum Granularity { DAY, WEEK, MONTH }
    enum View { DAILY, PERIOD, TREND, TREND_WEEK, TREND_MONTH, PAYMENTS, DISCOUNTS, REFUNDS, PROFIT, RANKINGS, INVENTORY, INVENTORY_MOVEMENT, HEALTH }
    Result query(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> metricCodes,int limit,MeasureFilter measure);
    ReportExportPort.Task export(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> metricCodes,int limit,String key,MeasureFilter measure);
    Result query(String entity,List<String> stores,String actor,View view,LocalDate from,LocalDate to,
            Integer version,Set<String> metricCodes,int limit);
}
