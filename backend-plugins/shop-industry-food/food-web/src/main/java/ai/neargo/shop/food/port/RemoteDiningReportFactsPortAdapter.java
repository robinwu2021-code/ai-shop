package ai.neargo.shop.food.port;

import java.time.LocalDate;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.food.spi.DiningReportInternalApi;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

public final class RemoteDiningReportFactsPortAdapter implements DiningReportFactsService {
    private final DiningReportInternalApi api;
    public RemoteDiningReportFactsPortAdapter(DiningReportInternalApi api) { this.api=api; }
    @Override public java.util.List<AffectedDay> affectedDays(Change change) {
        return ServiceCalls.call(ServiceName.FOOD,()->api.affectedDays(change));
    }
    @Override public SourceKey source() { return new SourceKey(Owner.FOOD,"ORDER"); }
    @Override public OperatingDay current(Scope scope) {
        return ServiceCalls.call(ServiceName.FOOD,()->api.currentDay(scope));
    }
    @Override public DayEvidence day(Scope scope,LocalDate date) {
        return ServiceCalls.call(ServiceName.FOOD,()->api.day(new DiningReportInternalApi.DayQuery(scope,date)));
    }
    @Override public SourceSnapshot readSnapshot(SourceQuery query) { return ServiceCalls.call(ServiceName.FOOD,()->api.facts(query)); }
}
