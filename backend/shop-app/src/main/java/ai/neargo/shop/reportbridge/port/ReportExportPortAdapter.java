package ai.neargo.shop.reportbridge.port;

import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.spi.report.ReportExportPort;

/** Cross-domain transport adapter; business behavior remains in the Service. */
public final class ReportExportPortAdapter implements ReportExportPort {
    private final ReportExportService service;
    public ReportExportPortAdapter(ReportExportService service) {this.service=service;}
    public Task create(Create command) {return service.create(command);}
    public Task get(Reference ref) {return service.get(ref);}
    public Download download(Reference ref) {return service.download(ref);}
}
