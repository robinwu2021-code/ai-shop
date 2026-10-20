package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Status;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.View;

public interface StoredValuePlanService {
    View create(CatalogScope scope, Create command, String key);
    View get(CatalogScope scope, String storeNo, String planNo);
    CursorPage<View> list(CatalogScope scope, String storeNo, String status, String cursor, int size);
    View status(CatalogScope scope, String planNo, Status command, String key);
}
