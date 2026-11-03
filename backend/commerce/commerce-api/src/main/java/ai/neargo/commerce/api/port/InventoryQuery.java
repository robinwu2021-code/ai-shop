package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record InventoryQuery(String tenantNo, String orderComponentNo) {}
