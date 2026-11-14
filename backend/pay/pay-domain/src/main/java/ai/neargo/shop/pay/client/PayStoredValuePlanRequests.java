package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans;

public final class PayStoredValuePlanRequests {
    private PayStoredValuePlanRequests() { }
    public record Create(CatalogScope scope, StoredValuePlans.Create command, String key) { }
    public record Get(CatalogScope scope, String storeNo, String planNo) { }
    public record List(CatalogScope scope, String storeNo, String status, String cursor, int size) { }
    public record Status(CatalogScope scope, String planNo, StoredValuePlans.Status command, String key) { }
}
