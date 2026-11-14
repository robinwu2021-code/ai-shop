package ai.neargo.shop.reportbridge.service.impl;
import ai.neargo.shop.spi.report.ReportRealtimePort.Query;
import ai.neargo.shop.spi.report.ReportRealtimePort.Result;


import java.time.Instant;
import java.util.List;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.reportbridge.service.ReportRealtimeService;
import ai.neargo.shop.reportbridge.service.ReportSourceCollectionService;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportDayPort;

/** Same source contracts and calculator as daily; never writes a report publication. */
public final class ReportRealtimeServiceImpl implements ReportRealtimeService {
    private final ReportSourceCollectionService sources;
    private final ReportDayPort days;
    private final ReportMetricCalculator calculator;
    private final int rowLimit;
    private final int version;
    public ReportRealtimeServiceImpl(ReportSourceCollectionService sources,ReportDayPort days,ReportMetricCalculator calculator,int rowLimit,int version) {
        this.sources=sources; this.days=days; this.calculator=calculator; this.rowLimit=rowLimit; this.version=version;
    }
    @Override public Result summary(Query query) {
        if (query.metricVersion()!=version) throw new IllegalArgumentException("REPORT_VERSION_NOT_SUPPORTED");
        var day=days.current(query.scope());
        var partition=new Partition(query.scope(),day.businessDate(),query.metricVersion());
        var collected=sources.collect(new SourceQuery(partition,day.evidence(),query.actorNo(),List.of(),rowLimit));
        Instant asOf=collected.stream().map(SourceSnapshot::asOfAt).min(Instant::compareTo).orElseThrow();
        var visible=collected.stream().filter(s->query.inventoryOnly()==(s.source().owner()==Owner.INVENTORY)).toList();
        var receipts=visible.stream().map(s->new SourceSnapshot(s.partition(),s.source(),s.snapshotRef(),s.sourceVersion(),
                s.asOfAt(),s.rowCount(),s.checksum(),s.coverage(),s.unknownReason(),List.of(),s.sourceRefs())).toList();
        return new Result(partition,day.evidence(),asOf,calculator.aggregate(partition,visible),receipts,false);
    }
}
