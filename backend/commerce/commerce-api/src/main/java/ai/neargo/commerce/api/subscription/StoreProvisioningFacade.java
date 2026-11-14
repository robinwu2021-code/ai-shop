package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.ActorContext;

/** Embedded host capability restricted to a trusted store creation transaction. */
public interface StoreProvisioningFacade {
    SubscriptionView initializeFree(ActorContext actor, InitializeStoreCommand command);
}
