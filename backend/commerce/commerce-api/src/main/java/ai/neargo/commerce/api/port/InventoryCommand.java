package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record InventoryCommand(String tenantNo, String orderNo, String orderComponentNo, String skuNo, long quantity) {}
