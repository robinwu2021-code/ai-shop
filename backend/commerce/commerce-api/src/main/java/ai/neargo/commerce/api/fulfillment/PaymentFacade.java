package ai.neargo.commerce.api.fulfillment;

import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.commerce.api.port.PaymentOutcome;

/**
 * startPayment authenticates buyer. recover is trusted scheduler/adapter-only, independent of
 * login.
 */
public interface PaymentFacade {
    PaymentOutcome startPayment(ActorContext actor, StartPaymentCommand command);

    FulfillmentResult recover(FulfillmentQuery query);
}
