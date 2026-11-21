package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.query.PointsOverviewService.Overview;
import ai.neargo.shop.pay.points.query.PointsOverviewService.ReceivablePage;

/**
 * Ops queries expose financial dimensions explicitly, without the legacy single-pool
 * interpretation.
 */
public interface PointsOpsAppService {
    Overview overview(String typeCode, String market);

    ReceivablePage receivables(String typeCode, String storeNo, long after, int limit);
}
