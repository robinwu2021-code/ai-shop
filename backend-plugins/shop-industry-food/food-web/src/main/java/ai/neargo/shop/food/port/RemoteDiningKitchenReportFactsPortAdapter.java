package ai.neargo.shop.food.port;

import ai.neargo.shop.food.service.DiningKitchenReportFactsService;
import ai.neargo.shop.food.spi.DiningKitchenReportInternalApi;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

/** Reads the original FOOD owner rather than reconstructing kitchen state on PLATFORM. */
public final class RemoteDiningKitchenReportFactsPortAdapter
        implements DiningKitchenReportFactsService {
    private final DiningKitchenReportInternalApi api;

    public RemoteDiningKitchenReportFactsPortAdapter(DiningKitchenReportInternalApi api) {
        this.api = api;
    }

    @Override
    public AffectedDay affectedDay(Change change) {
        return ServiceCalls.call(ServiceName.FOOD, () -> api.affectedDay(change));
    }

    @Override
    public SourceKey source() {
        return new SourceKey(Owner.FOOD, "KITCHEN");
    }

    @Override
    public SourceSnapshot readSnapshot(SourceQuery query) {
        return ServiceCalls.call(ServiceName.FOOD, () -> api.facts(query));
    }
}
