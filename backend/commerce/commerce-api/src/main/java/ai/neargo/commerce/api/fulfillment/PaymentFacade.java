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

    /** Trusted refresh also queries closed attempts for late cash; callers must authorize order ownership first. */
    default FulfillmentResult refresh(FulfillmentQuery query) { return recover(query); }
}
