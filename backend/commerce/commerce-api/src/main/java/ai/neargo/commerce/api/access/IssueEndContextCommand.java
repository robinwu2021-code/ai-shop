package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

public record IssueEndContextCommand(ActorContext actor, StoreRef requestedStore, StoreRef resolvedStore, String entitlementCode, String clientId, String audience) {}
