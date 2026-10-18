package ai.neargo.shop.payclient;

import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicy;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicyChange;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService.Inspection;

public interface OrderPointsIntegrationAppService {
    IntegrationPolicy policy(String store, String scene);

    IntegrationPolicy change(IntegrationPolicyChange change, String actor);

    Inspection inspect(String subOrderNo);
}
