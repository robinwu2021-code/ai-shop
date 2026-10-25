package ai.neargo.shop.reportbridge.service;

import java.time.LocalDate;
import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.Publication;

public interface ReportDailyService {
    record Outcome(int published,int failed,int deferred,int maintenanceFailed) {}
    Outcome runDaily(String runId, LocalDate throughDate);
    /** Caller must authorize request scope and actor; source and storage validate original ownership again. */
    Publication rebuild(BuildReq request);
}
