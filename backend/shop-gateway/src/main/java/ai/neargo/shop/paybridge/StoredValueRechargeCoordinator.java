package ai.neargo.shop.paybridge;

import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;

public interface StoredValueRechargeCoordinator {
    StoredValueRechargeProgress create(EntitlementContext context, String entityNo, RechargeIntentCreate command,
                                       String key);
    StoredValueRechargeProgress submit(EntitlementContext context, String entityNo, SubmitRecharge command);
    StoredValueRechargeProgress receive(EntitlementContext context, String entityNo, ReceiveRecharge command);
    StoredValueRechargeProgress cancel(EntitlementContext context, String entityNo, CancelRecharge command);
    StoredValueRechargeProgress query(EntitlementContext context, String entityNo, String rechargeNo);
    StoredValueRechargeProgress recover(EntitlementContext context, String entityNo, String rechargeNo);
}
