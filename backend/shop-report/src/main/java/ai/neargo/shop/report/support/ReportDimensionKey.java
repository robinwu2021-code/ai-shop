package ai.neargo.shop.report.support;

import java.util.Arrays;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;

public final class ReportDimensionKey {
    private ReportDimensionKey() {}
    public static String of(MetricFact fact) {
        return ReportEvidenceCodec.hash(Arrays.asList(fact.aggregationSet(),fact.attribution(),fact.dimensions(),fact.value().unit(),fact.value().currency()));
    }
}
