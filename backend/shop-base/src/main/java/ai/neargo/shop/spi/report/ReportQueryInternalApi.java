package ai.neargo.shop.spi.report;

import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface ReportQueryInternalApi {
    @PostExchange("/internal/reports/food/realtime") ReportRealtimePort.Result realtime(@RequestBody ReportRealtimePort.Query request);
    @PostExchange("/internal/reports/food/metrics") List<ReportMetricCatalogPort.MetricDefinition> metrics();
    String QUERY="/internal/reports/food/query";
    @PostExchange(QUERY) ReportQueryPort.Result query(@RequestBody ReportQueryPort.Query request);
}
