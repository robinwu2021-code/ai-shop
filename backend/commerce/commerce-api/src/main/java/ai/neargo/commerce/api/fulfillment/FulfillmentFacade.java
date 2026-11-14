package ai.neargo.commerce.api.fulfillment;

import ai.neargo.commerce.api.common.*;

/** Trusted adapters only: payment facts must already be authenticated and verified; never bind client paid flags. */
public interface FulfillmentFacade {
 FulfillmentResult confirmPayment(VerifiedPaymentFact fact);
  FulfillmentResult fulfill(FulfillmentQuery query);
  FulfillmentResult confirmShipment(HardwareShipmentCommand command);

}
