package ai.neargo.commerce.api.access;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record AccessCheck(ActorContext actor, StoreRef requestedStore, StoreRef resolvedStore, Optional<StoreRef> pathStore, String entitlementCode, String clientId, String requiredCapability, Optional<EndContext> endContext) {}
