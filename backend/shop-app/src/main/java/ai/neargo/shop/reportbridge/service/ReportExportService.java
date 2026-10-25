package ai.neargo.shop.reportbridge.service;

import ai.neargo.shop.spi.report.ReportExportPort.Create;
import ai.neargo.shop.spi.report.ReportExportPort.Reference;
import ai.neargo.shop.spi.report.ReportExportPort.Task;
import ai.neargo.shop.spi.report.ReportExportPort.Download;

public interface ReportExportService {
    Task create(Create command);
    Task get(Reference reference);
    Download download(Reference reference);
    void execute(Reference reference);
    void maintain(String tenant,String entity,int limit);
}
