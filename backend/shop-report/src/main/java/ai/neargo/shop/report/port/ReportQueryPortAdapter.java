package ai.neargo.shop.report.port;

import ai.neargo.shop.report.service.ReportQueryService;
import ai.neargo.shop.spi.report.ReportQueryPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort;
import ai.neargo.shop.spi.report.ReportMetricCatalogPort.MetricDefinition;

/** Cross-domain transport adapter; business behavior remains in the Service. */
public final class ReportQueryPortAdapter implements ReportQueryPort, ReportMetricCatalogPort {
    private final ReportQueryService service;
    public ReportQueryPortAdapter(ReportQueryService service) {this.service=service;}
    public Result query(Query query) {return service.query(query);}
    public java.util.List<MetricDefinition> metrics() {return service.metrics();}
}
