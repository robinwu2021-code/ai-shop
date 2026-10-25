package ai.neargo.shop.spi.report;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface ReportExportInternalApi {
    String CREATE="/internal/reports/exports/create";
    String GET="/internal/reports/exports/get";
    String DOWNLOAD="/internal/reports/exports/download";
    @PostExchange(CREATE) ReportExportPort.Task create(@RequestBody ReportExportPort.Create request);
    @PostExchange(GET) ReportExportPort.Task get(@RequestBody ReportExportPort.Reference request);
    @PostExchange(DOWNLOAD) ReportExportPort.Download download(@RequestBody ReportExportPort.Reference request);
}
