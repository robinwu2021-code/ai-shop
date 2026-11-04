package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.*;

/** initializeFree is a trusted host create-store capability sharing its local transaction, not a public arbitrary-store grant endpoint. */
public interface SubscriptionFacade extends StoreProvisioningFacade {
  SubscriptionView grant(ActorContext actor, GrantCommand command);
  SubscriptionView revoke(ActorContext actor, RevokeCommand command);
  SubscriptionView subscription(ActorContext actor, SubscriptionQuery query);

}
