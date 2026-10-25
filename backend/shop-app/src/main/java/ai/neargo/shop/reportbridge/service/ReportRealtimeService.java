package ai.neargo.shop.reportbridge.service;

import ai.neargo.shop.spi.report.ReportRealtimePort.Query;
import ai.neargo.shop.spi.report.ReportRealtimePort.Result;

public interface ReportRealtimeService {
    Result summary(Query query);
}
