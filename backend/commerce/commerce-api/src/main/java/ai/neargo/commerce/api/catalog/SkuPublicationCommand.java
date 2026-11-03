package ai.neargo.commerce.api.catalog;

import ai.neargo.commerce.api.common.*;

public record SkuPublicationCommand(String operationNo, String skuNo, long expectedVersion) {}
