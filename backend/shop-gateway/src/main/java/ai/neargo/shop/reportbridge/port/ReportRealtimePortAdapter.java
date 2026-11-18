package ai.neargo.shop.reportbridge.port;

import ai.neargo.shop.reportbridge.service.ReportRealtimeService;
import ai.neargo.shop.spi.report.ReportRealtimePort;

/** Cross-domain transport adapter; business behavior remains in the Service. */
public final class ReportRealtimePortAdapter implements ReportRealtimePort {
    private final ReportRealtimeService service;
    public ReportRealtimePortAdapter(ReportRealtimeService service) {this.service=service;}
    public Result summary(Query query) {return service.summary(query);}
}
