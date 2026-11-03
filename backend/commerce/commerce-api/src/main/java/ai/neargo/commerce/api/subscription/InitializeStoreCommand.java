package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.*;

public record InitializeStoreCommand(StoreRef store, String businessTypeCode, String operationNo) {}
