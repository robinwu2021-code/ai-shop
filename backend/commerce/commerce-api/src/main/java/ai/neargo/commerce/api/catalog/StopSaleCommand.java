package ai.neargo.commerce.api.catalog;

import ai.neargo.commerce.api.common.*;

public record StopSaleCommand(String operationNo, String skuNo, long expectedVersion, String reason) {}
