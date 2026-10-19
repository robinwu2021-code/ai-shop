package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementBatchAppService implements EntitlementBatchAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementBatchAppService(PayInternalApi pay) { this.pay = pay; }
    @Override public BatchView create(CatalogScope scope, CreateBatch command, String key) {
        return RemoteEntitlementCalls.call(() ->
                pay.entBatchCreate(new PayEntitlementRequests.BatchCreate(scope, command, key)));
    }
    @Override public BatchView get(CatalogScope scope, String no) {
        return RemoteEntitlementCalls.call(() -> pay.entBatchGet(new PayEntitlementRequests.BatchGet(scope, no)));
    }
    @Override public BatchView resume(CatalogScope scope, String no, int limit) {
        return RemoteEntitlementCalls.call(() ->
                pay.entBatchResume(new PayEntitlementRequests.BatchResume(scope, no, limit)));
    }
}
