package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;

public interface StoredValueCreditService {
    RechargeView credit(EntitlementContext context, CreditRecharge command);
}
