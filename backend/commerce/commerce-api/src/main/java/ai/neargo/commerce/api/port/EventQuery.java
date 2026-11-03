package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record EventQuery(String tenantNo, String afterCursor, int limit) {}
