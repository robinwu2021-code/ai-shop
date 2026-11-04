package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.ActorContext;

/**
 * OPS-only zero-value source management. Paid/credit-funded reversal remains a refund operation.
 */
public interface SourceManagementFacade {
    SubscriptionView subscription(ActorContext actor, SubscriptionQuery query);

    SubscriptionView grant(ActorContext actor, GrantCommand command);

    SubscriptionView revoke(ActorContext actor, RevokeCommand command);
}
