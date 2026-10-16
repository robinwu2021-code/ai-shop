package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.query.PointsOverviewService;
import ai.neargo.shop.pay.points.query.PointsOverviewService.Overview;
import ai.neargo.shop.pay.points.query.PointsOverviewService.ReceivablePage;
import ai.neargo.shop.payclient.PointsOpsAppService;

import org.springframework.stereotype.Service;

@Service
public class PointsOpsAppServiceImpl implements PointsOpsAppService {
    private final PointsOverviewService overview;

    public PointsOpsAppServiceImpl(PointsOverviewService overview) {
        this.overview = overview;
    }

    @Override
    public ReceivablePage receivables(String typeCode, String storeNo, long after, int limit) {
        return overview.receivables(typeCode, storeNo, after, limit);
    }

    @Override
    public Overview overview(String typeCode, String market) {
        return overview.overview(typeCode, market);
    }
}
