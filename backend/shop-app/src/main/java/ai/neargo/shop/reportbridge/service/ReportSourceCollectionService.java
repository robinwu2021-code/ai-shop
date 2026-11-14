package ai.neargo.shop.reportbridge.service;

import java.util.List;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;

public interface ReportSourceCollectionService {
    List<SourceSnapshot> collect(SourceQuery query);
}
