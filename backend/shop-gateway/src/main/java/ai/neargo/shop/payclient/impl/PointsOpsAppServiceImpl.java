package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.query.PointsOverviewService;
import ai.neargo.shop.pay.points.query.PointsOverviewService.Overview;
import ai.neargo.shop.pay.points.query.PointsOverviewService.ReceivablePage;
import ai.neargo.shop.payclient.PointsOpsAccess;
import ai.neargo.shop.payclient.PointsOpsAppService;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PointsOpsAppServiceImpl implements PointsOpsAppService {
    @Autowired(required = false)
    private StoreDirectoryPort stores;

    private final PointsOverviewService overview;

    public PointsOpsAppServiceImpl(PointsOverviewService overview) {
        this.overview = overview;
    }

    @Override
    public ReceivablePage receivables(String typeCode, String storeNo, long after, int limit) {
        PointsOpsAccess.store(stores, storeNo);
        return PointsOpsErrors.call(() -> overview.receivables(typeCode, storeNo, after, limit));
    }

    @Override
    public Overview overview(String typeCode, String market) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> overview.overview(typeCode, market));
    }
}
