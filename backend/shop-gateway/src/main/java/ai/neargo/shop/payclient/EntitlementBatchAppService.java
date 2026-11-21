package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;

public interface EntitlementBatchAppService {
    BatchView create(CatalogScope scope, CreateBatch command, String key);
    BatchView get(CatalogScope scope, String no);
    BatchView resume(CatalogScope scope, String no, int limit);
}
