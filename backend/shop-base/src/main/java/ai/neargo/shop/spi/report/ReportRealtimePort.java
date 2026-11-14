package ai.neargo.shop.spi.report;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;

public interface ReportRealtimePort {
    record Query(Scope scope,String actorNo,int metricVersion,boolean inventoryOnly) {
        public Query { Objects.requireNonNull(scope); ReportContracts.identifier(actorNo,64);
            if (metricVersion<1) throw new IllegalArgumentException("REPORT_INVALID_VERSION"); }
    }
    record Result(Partition partition,DayEvidence day,Instant asOfAt,List<MetricFact> facts,List<SourceSnapshot> sources,boolean isFinal) {
        public Result { facts=List.copyOf(facts); sources=List.copyOf(sources);
            if (isFinal) throw new IllegalArgumentException("REPORT_LIVE_CANNOT_BE_FINAL"); }
    }
    Result summary(Query query);
}
