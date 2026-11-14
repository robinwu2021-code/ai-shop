package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public interface StorefrontFacade {
    StorefrontView catalog(ActorContext actor, StoreRef store);
}
