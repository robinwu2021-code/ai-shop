package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService;

import org.springframework.stereotype.Service;

@Service
public class OrderPointsIntegrationAppServiceImpl implements OrderPointsIntegrationAppService {
    private final PointsIntegrationPolicyService service;
    private final StoreDirectoryPort stores;
    private final OrderPointsBindingService bindings;
    private final OrderPointsRecoveryService recovery;

    public OrderPointsIntegrationAppServiceImpl(
            PointsIntegrationPolicyService service, StoreDirectoryPort stores,
            OrderPointsBindingService bindings, OrderPointsRecoveryService recovery) {
        this.service = service;
        this.stores = stores;
        this.bindings = bindings;
        this.recovery = recovery;
    }

    @Override
    public IntegrationPolicy policy(String store, String scene) {
        PointsOpsAccess.store(stores, store);
        return PointsOpsErrors.call(() -> service.policy(store, scene));
    }

    @Override
    public IntegrationPolicy change(IntegrationPolicyChange c, String actor) {
        PointsOpsAccess.store(stores, c.storeNo());
        return PointsOpsErrors.call(() -> service.change(c, actor));
    }

    @Override
    public OrderPointsRecoveryService.Inspection inspect(String subOrderNo) {
        if (subOrderNo == null || subOrderNo.isBlank())
            throw new IllegalArgumentException("POINTS_RECOVERY_SUB_REQUIRED");
        var binding = bindings.find(subOrderNo);
        if (binding == null) throw new IllegalArgumentException("POINTS_SOURCE_BINDING_UNKNOWN");
        PointsOpsAccess.store(stores, binding.admission().storeNo());
        return PointsOpsErrors.call(() -> recovery.inspect(subOrderNo));
    }
}
