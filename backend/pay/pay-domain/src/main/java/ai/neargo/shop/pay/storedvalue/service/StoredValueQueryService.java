package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;

public interface StoredValueQueryService {
    BalanceView balance(EntitlementContext context, BalanceQuery query);

    CursorPage<StoreItem> stores(EntitlementContext context, StoreQuery query);

    CursorPage<UsageItem> consumptions(EntitlementContext context, ConsumptionQuery query);
}
