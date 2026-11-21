package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchService;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class EntitlementBatchAppServiceImpl implements EntitlementBatchAppService {
    private final EntitlementIssueBatchService owner;
    public EntitlementBatchAppServiceImpl(EntitlementIssueBatchService owner) { this.owner = owner; }
    @Override public BatchView create(CatalogScope scope, CreateBatch command, String key) {
        return owner.create(scope, command, key);
    }
    @Override public BatchView get(CatalogScope scope, String no) {
        return owner.get(scope, no);
    }
    @Override public BatchView resume(CatalogScope scope, String no, int limit) {
        return owner.resume(scope, no, limit);
    }
}
