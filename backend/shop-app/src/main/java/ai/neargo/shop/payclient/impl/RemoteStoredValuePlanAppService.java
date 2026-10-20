package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValuePlanAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValuePlanRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Status;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.View;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="shop.pay.deployment", havingValue="standalone")
public class RemoteStoredValuePlanAppService implements StoredValuePlanAppService {
    private final PayInternalApi plans;
    public RemoteStoredValuePlanAppService(PayInternalApi plans) { this.plans=plans; }
    @Override public View create(CatalogScope s, Create c, String key) { return RemoteEntitlementCalls.call(() -> plans.svPlanCreate(new PayStoredValuePlanRequests.Create(s, c, key))); }
    @Override public View get(CatalogScope s, String store, String no) { return RemoteEntitlementCalls.call(() -> plans.svPlanGet(new PayStoredValuePlanRequests.Get(s, store, no))); }
    @Override public CursorPage<View> list(CatalogScope s, String store, String status, String cursor, int size) { return RemoteEntitlementCalls.call(() -> plans.svPlanList(new PayStoredValuePlanRequests.List(s, store, status, cursor, size))); }
    @Override public View status(CatalogScope s, String no, Status c, String key) { return RemoteEntitlementCalls.call(() -> plans.svPlanStatus(new PayStoredValuePlanRequests.Status(s, no, c, key))); }
}
