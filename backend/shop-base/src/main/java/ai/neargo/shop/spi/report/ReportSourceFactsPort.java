package ai.neargo.shop.spi.report;

import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;

/** An owner exposes a bounded, scoped read snapshot; refresh/payment mutations are forbidden. */
public interface ReportSourceFactsPort {
    SourceKey source();
    SourceSnapshot readSnapshot(SourceQuery query);
}
