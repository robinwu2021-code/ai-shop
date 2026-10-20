package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicy;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicyChange;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.Mode;

import org.springframework.stereotype.Service;

/** All stores participate; per-store integration overrides are not supported. */
@Service
public class PointsIntegrationPolicyServiceImpl implements PointsIntegrationPolicyService {
    @Override
    public IntegrationPolicy policy(String store, String scene) {
        require(nonblank(store) && PayModes.isValid(scene), "POINTS_INTEGRATION_INPUT_INVALID");
        return new IntegrationPolicy(
                store, scene, Mode.ACTIVE, true, true, 0, "SYSTEM", "ALL_STORES_DEFAULT", 0);
    }

    @Override
    public IntegrationPolicy change(IntegrationPolicyChange command, String actor) {
        throw new IllegalArgumentException("POINTS_STORE_POLICY_UNSUPPORTED");
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid, String error) {
        if (!valid) throw new IllegalArgumentException(error);
    }
}
