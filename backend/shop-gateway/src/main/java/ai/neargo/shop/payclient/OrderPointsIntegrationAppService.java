package ai.neargo.shop.payclient;

import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicy;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicyChange;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Inspection;
import ai.neargo.shop.trade.dto.OpsAfterSaleVO;
import ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.ManualPage;

public interface OrderPointsIntegrationAppService {
    ai.neargo.shop.trade.order.points.OrderPointsCoordinator.StoreObligations obligations(String storeNo);

    IntegrationPolicy policy(String store, String scene);

    IntegrationPolicy change(IntegrationPolicyChange change, String actor);

    Inspection inspect(String subOrderNo);

    ManualPage manualRefunds(String storeNo, String after, int limit);

    OpsAfterSaleVO closeCompensatedRefund(String afterSaleNo, String actor, String reason);

    OpsAfterSaleVO retryCompensatedRefund(String afterSaleNo, String actor, String reason);
}
