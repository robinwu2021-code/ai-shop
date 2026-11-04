package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.*;

public record RevokeCommand(StoreRef store, String sourceNo, String operationNo, String reason, long expectedVersion) {}
