package ai.neargo.shop.payclient;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.common.CursorPage;

public interface StoredValueRechargeAppService {
    RechargeView create(EntitlementContext context, String entityNo, CreateRecharge command);
    RechargeView submit(EntitlementContext context, String entityNo, SubmitRecharge command);
    RechargeView receive(EntitlementContext context, String entityNo, ReceiveRecharge command);
    RechargeView cancel(EntitlementContext context, String entityNo, CancelRecharge command);
    RechargeView credit(EntitlementContext context, CreditRecharge command);
    RechargeView query(EntitlementContext context, String entityNo, String rechargeNo);
    BalanceView balance(EntitlementContext context, BalanceQuery query);
    CursorPage<StoreItem> stores(EntitlementContext context, StoreQuery query);
    CursorPage<UsageItem> consumptions(EntitlementContext context, ConsumptionQuery query);
}
