package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;

public interface StoredValueRechargeService {
    RechargeView create(EntitlementContext context, String entityNo, CreateRecharge command);
    RechargeView submit(EntitlementContext context, String entityNo, SubmitRecharge command);
    RechargeView receive(EntitlementContext context, String entityNo, ReceiveRecharge command);
    RechargeView cancel(EntitlementContext context, String entityNo, CancelRecharge command);
}
