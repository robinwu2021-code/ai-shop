package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;

/** Each bounded resume processes original recipients; creating the batch never accepts a public user list. */
public interface EntitlementIssueBatchService {
    BatchView create(CatalogScope scope, CreateBatch command, String key);
    BatchView resume(CatalogScope scope, String batchNo, int limit);
    BatchView get(CatalogScope scope, String batchNo);
}
