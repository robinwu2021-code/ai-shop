package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;

public interface StoredValuePostingService {
    RechargeView creditReceived(EntitlementContext context, CreditRecharge command);
}
