package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

import java.util.Optional;

public record AccessCheck(
        ActorContext actor,
        StoreRef requestedStore,
        StoreRef resolvedStore,
        Optional<StoreRef> pathStore,
        String endCode,
        String entitlementCode,
        String clientId,
        String requiredCapability,
        Optional<String> endContext) {
    @Override
    public String toString() {
        return "AccessCheck[redacted]";
    }
}
