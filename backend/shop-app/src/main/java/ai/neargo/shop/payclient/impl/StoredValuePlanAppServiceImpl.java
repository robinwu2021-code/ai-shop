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
@ConditionalOnProperty(name="shop.pay.deployment", havingValue="embedded", matchIfMissing=true)
public class StoredValuePlanAppServiceImpl implements StoredValuePlanAppService {
    private final StoredValuePlanService plans;
    public StoredValuePlanAppServiceImpl(StoredValuePlanService plans) { this.plans=plans; }
    @Override public View create(CatalogScope s, Create c, String key) { return plans.create(s, c, key); }
    @Override public View get(CatalogScope s, String store, String no) { return plans.get(s, store, no); }
    @Override public CursorPage<View> list(CatalogScope s, String store, String status, String cursor, int size) { return plans.list(s, store, status, cursor, size); }
    @Override public View status(CatalogScope s, String no, Status c, String key) { return plans.status(s, no, c, key); }
}
