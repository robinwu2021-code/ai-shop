package ai.neargo.shop.report.service;

import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;
import java.util.List;

public interface ReportQueryService {
    Result query(Query query);
    List<MetricDefinition> metrics();
}
