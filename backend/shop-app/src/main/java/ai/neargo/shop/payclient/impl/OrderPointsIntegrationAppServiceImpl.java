package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.springframework.stereotype.Service;

@Service
public class OrderPointsIntegrationAppServiceImpl implements OrderPointsIntegrationAppService {
    private final PointsIntegrationPolicyService service;
    private final StoreDirectoryPort stores;

    public OrderPointsIntegrationAppServiceImpl(
            PointsIntegrationPolicyService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
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
}
