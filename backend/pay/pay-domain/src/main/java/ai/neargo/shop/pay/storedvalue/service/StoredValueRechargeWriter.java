package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;

/** Pay transaction boundary. All reverse-source HTTP reads precede entry. */
public interface StoredValueRechargeWriter {
    RechargeView existing(EntitlementContext context, String entityNo, CreateRecharge command);
    boolean exists(EntitlementContext context, String entityNo, String rechargeNo);
    RechargeView create(EntitlementContext context, String entityNo, CreateRecharge command, SourceFact source);
    RechargeView submit(EntitlementContext context, String entityNo, SubmitRecharge command);
    RechargeView receive(EntitlementContext context, String entityNo, ReceiveRecharge command);
    RechargeView cancel(EntitlementContext context, String entityNo, CancelRecharge command, SourceFact source);
}
